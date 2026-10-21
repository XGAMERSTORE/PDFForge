package com.xgamerstore.pdfforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-activity, dependency-light Android PDF studio. */
public class MainActivity extends Activity {
    private static final int PICK = 401, CREATE = 402;
    private static final String PREF = "pdf_forge";
    private static final int[] PALETTE = {0xFF6654FA,0xFF12A89D,0xFFE39739,0xFFEF647A,0xFF4796E9,0xFF8262D7,0xFF344C82,0xFF2D9386};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> selected = new ArrayList<>();
    private Tool pending;
    private String parameter = "";
    private boolean dark;
    private int tab;
    private LinearLayout screen;
    private SharedPreferences preferences;
    private BillingManager billing;
    private final ArrayList<Tool> toolList = new ArrayList<>();

    private static class Tool {
        final int title, subtitle;
        final String symbol;
        final int color;
        final PdfEngine.Action action;
        final OfficeEngine.Action officeAction;
        boolean isPremium() {
            if(action==PdfEngine.Action.PDF_TO_TEXT || action==PdfEngine.Action.SIGN)return true;
            if(officeAction==null)return false;
            return officeAction!=OfficeEngine.Action.DOCX_PDF
                && officeAction!=OfficeEngine.Action.TXT_PDF
                && officeAction!=OfficeEngine.Action.CSV_PDF;
        }
        Tool(int title, int subtitle, String symbol, int color, PdfEngine.Action action) {
            this.title = title; this.subtitle = subtitle; this.symbol = symbol;
            this.color = color; this.action = action;this.officeAction=null;
        }
        Tool(int title,int subtitle,String symbol,int color,OfficeEngine.Action officeAction,boolean isOffice){
            this.title=title;this.subtitle=subtitle;this.symbol=symbol;this.color=color;
            this.action=null;this.officeAction=officeAction;
        }
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguageHelper.wrap(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREF, MODE_PRIVATE);
        dark = preferences.getBoolean("dark", false);
        PDFBoxResourceLoader.init(getApplicationContext());
        toolList.add(new Tool(R.string.merge,R.string.merge_desc,"⇄",PALETTE[0],PdfEngine.Action.MERGE));
        toolList.add(new Tool(R.string.split,R.string.split_desc,"⇵",PALETTE[1],PdfEngine.Action.SPLIT));
        toolList.add(new Tool(R.string.extract,R.string.extract_desc,"▣",PALETTE[2],PdfEngine.Action.EXTRACT));
        toolList.add(new Tool(R.string.rotate,R.string.rotate_desc,"↻",PALETTE[3],PdfEngine.Action.ROTATE));
        toolList.add(new Tool(R.string.image_to_pdf,R.string.image_to_pdf_desc,"▧",PALETTE[4],PdfEngine.Action.IMAGE_TO_PDF));
        toolList.add(new Tool(R.string.pdf_to_image,R.string.pdf_to_image_desc,"▤",PALETTE[5],PdfEngine.Action.PDF_TO_IMAGE));
        toolList.add(new Tool(R.string.pdf_to_text,R.string.pdf_to_text_desc,"T",PALETTE[6],PdfEngine.Action.PDF_TO_TEXT));
        toolList.add(new Tool(R.string.sign,R.string.sign_desc,"✎",PALETTE[3],PdfEngine.Action.SIGN));
        toolList.add(new Tool(R.string.word_pdf,R.string.word_pdf_desc,"W",PALETTE[0],OfficeEngine.Action.DOCX_PDF,true));
        toolList.add(new Tool(R.string.pdf_word,R.string.pdf_word_desc,"W↗",PALETTE[4],OfficeEngine.Action.PDF_DOCX,true));
        toolList.add(new Tool(R.string.word_text,R.string.word_text_desc,"W→T",PALETTE[6],OfficeEngine.Action.DOCX_TXT,true));
        toolList.add(new Tool(R.string.text_pdf,R.string.text_pdf_desc,"T→",PALETTE[1],OfficeEngine.Action.TXT_PDF,true));
        toolList.add(new Tool(R.string.excel_pdf,R.string.excel_pdf_desc,"X",PALETTE[1],OfficeEngine.Action.XLSX_PDF,true));
        toolList.add(new Tool(R.string.excel_csv,R.string.excel_csv_desc,"X→",PALETTE[3],OfficeEngine.Action.XLSX_CSV,true));
        toolList.add(new Tool(R.string.csv_excel,R.string.csv_excel_desc,"→X",PALETTE[0],OfficeEngine.Action.CSV_XLSX,true));
        toolList.add(new Tool(R.string.csv_pdf,R.string.csv_pdf_desc,"CSV",PALETTE[5],OfficeEngine.Action.CSV_PDF,true));
        toolList.add(new Tool(R.string.powerpoint_pdf,R.string.powerpoint_pdf_desc,"P",PALETTE[2],OfficeEngine.Action.PPTX_PDF,true));
        toolList.add(new Tool(R.string.preview,R.string.preview_desc,"↗",PALETTE[7],null));
        billing=new BillingManager(this,new BillingManager.Listener(){
            @Override public void changed(){
                if(!isFinishing()&&!isDestroyed()&&(tab==3||tab==4))draw();
            }
            @Override public void message(int resource){
                if(!isFinishing()&&!isDestroyed())
                    Toast.makeText(MainActivity.this,resource,Toast.LENGTH_LONG).show();
            }
        });
        draw();
        billing.connect();
    }

    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if(!isFinishing())recreate();
    }

    private int bg() { return dark ? 0xFF0E1020 : 0xFFF6F7FC; }
    private int panel() { return dark ? 0xFF1B1F35 : Color.WHITE; }
    private int ink() { return dark ? 0xFFFCFCFF : 0xFF17192C; }
    private int secondary() { return dark ? 0xFFACB1CA : 0xFF71788B; }
    private int border() { return dark ? 0xFF30364D : 0xFFE9ECF3; }
    private int accent() { return dark ? 0xFFA99EFF : 0xFF6654FA; }
    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private View gap(int height) { View v = new View(this); v.setLayoutParams(lp(1,height)); return v; }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius)); return g;
    }
    private GradientDrawable outlined(int color, int radius, int stroke) {
        GradientDrawable g = shape(color,radius);
        g.setStroke(dp(1),stroke); return g;
    }
    private TextView text(String message, float size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(message); t.setTextColor(color);
        t.setTextSize(size); t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private TextView label(int resource, float size, boolean bold, int color) {
        return text(getString(resource),size,bold,color);
    }
    private void inset(View v, int a, int b, int c, int d) { v.setPadding(dp(a),dp(b),dp(c),dp(d)); }

    private void draw() {
        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());
        int flags = 0;
        if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        LinearLayout root = vertical();
        root.setBackgroundColor(bg());
        root.setFitsSystemWindows(true);
        screen = root;
        setContentView(root);
        topBar(root);
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout content = vertical(); inset(content,20,12,20,28);
        scroller.addView(content);
        switch(tab) {
            case 1: toolsPage(content); break;
            case 2: recentPage(content); break;
            case 3: settingsPage(content); break;
            case 4: premiumPage(content); break;
            default: homePage(content);
        }
        bottomNavigation(root);
    }

    private void topBar(LinearLayout root) {
        LinearLayout bar = horizontal(); inset(bar,20,18,20,12);
        root.addView(bar,lp(-1,-2));
        FrameLayout emblem = new FrameLayout(this); emblem.setBackground(shape(0xFF6654FA,14));
        bar.addView(emblem,lp(46,46));
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.ic_logo);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(42),dp(42),Gravity.CENTER);
        emblem.addView(image,iconParams);
        LinearLayout brand = vertical(); inset(brand,12,0,0,0);
        bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1f));
        brand.addView(text("PDFForge",22,true,ink()),lp(-1,-2));
        brand.addView(label(R.string.tagline,11,false,secondary()),lp(-1,-2));
        TextView avatar = text("✦",23,true,accent());
        avatar.setGravity(Gravity.CENTER); avatar.setBackground(outlined(panel(),16,border()));
        bar.addView(avatar,lp(45,45));
        avatar.setOnClickListener(v->{tab=4;draw();});
    }

    private void homePage(LinearLayout c) {
        hero(c);
        c.addView(gap(14));
        if(!billing.isPremium()) premiumTeaser(c);
        c.addView(gap(24));
        header(c,R.string.popular_tools);
        c.addView(gap(13));
        addToolGrid(c,0,6);
        c.addView(gap(25));
        header(c,R.string.recent_files);
        c.addView(gap(12));
        addRecent(c,true);
        c.addView(gap(18));
        privacyCard(c);
    }

    private void hero(LinearLayout container) {
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF242250,0xFF5347BA,0xFF7467FF});
        gradient.setCornerRadius(dp(28));
        LinearLayout hero = vertical(); inset(hero,24,23,24,24);
        hero.setBackground(gradient); hero.setElevation(dp(7));
        container.addView(hero,lp(-1,-2));
        TextView pill = text("✦   "+getString(R.string.offline).toUpperCase(Locale.ROOT),11,true,Color.WHITE);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(shape(0x405F6EFF,30));
        hero.addView(pill,lp(132,30));
        hero.addView(gap(21));
        TextView headline = label(R.string.welcome,29,true,Color.WHITE);
        headline.setMaxLines(3);
        hero.addView(headline,lp(-1,-2));
        hero.addView(gap(10));
        TextView detail = label(R.string.hero_desc,14,false,0xFFE6E4FF);
        detail.setLineSpacing(dp(4),1f);
        hero.addView(detail,lp(-1,-2));
        hero.addView(gap(21));
        TextView action = text(getString(R.string.start_creating)+"  →",14,true,0xFF2E236F);
        action.setGravity(Gravity.CENTER);
        action.setBackground(shape(Color.WHITE,13));
        hero.addView(action,lp(-1,47));
        action.setOnClickListener(v->{tab=1;draw();});
    }

    private void header(LinearLayout target, int title) {
        LinearLayout line = horizontal();
        target.addView(line,lp(-1,-2));
        TextView titleText = label(title,19,true,ink());
        line.addView(titleText,new LinearLayout.LayoutParams(0,-2,1f));
        if (title==R.string.popular_tools || title==R.string.recent_files) {
            TextView next = text("→",22,true,accent());
            line.addView(next,lp(30,32));
            next.setGravity(Gravity.CENTER);
            next.setOnClickListener(v->{tab=title==R.string.popular_tools?1:2;draw();});
        }
    }

    private void toolsPage(LinearLayout c) {
        c.addView(label(R.string.all_tools,27,true,ink()),lp(-1,-2));
        c.addView(gap(9));
        c.addView(label(R.string.hero_desc,14,false,secondary()),lp(-1,-2));
        c.addView(gap(24));
        addToolGrid(c,0,toolList.size());
        c.addView(gap(22));
        privacyCard(c);
    }

    private void addToolGrid(LinearLayout target, int start, int end) {
        for(int i=start;i<end;i+=2) {
            LinearLayout row = horizontal();
            target.addView(row,lp(-1,-2));
            for(int j=i;j<Math.min(i+2,end);j++) {
                Tool tool=toolList.get(j);
                LinearLayout tile = vertical();
                inset(tile,16,18,14,16);
                tile.setBackground(outlined(panel(),21,border()));
                tile.setElevation(dp(1));
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0,dp(158),1f);
                if(j%2==1) tileParams.leftMargin=dp(10);
                row.addView(tile,tileParams);
                TextView icon=text(tool.symbol,26,true,tool.color);
                icon.setGravity(Gravity.CENTER);
                icon.setBackground(shape(dark?0xFF30334A:0xFFF0F0FF,14));
                tile.addView(icon,lp(45,45));
                tile.addView(gap(14));
                TextView name=text(getString(tool.title)
                    +(tool.isPremium()&&!billing.isPremium()?"  ✦":""),14,true,ink());
                name.setMaxLines(2);
                tile.addView(name,lp(-1,-2));
                tile.addView(gap(5));
                TextView desc=label(tool.subtitle,11,false,secondary());
                desc.setMaxLines(2);
                desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tile.addView(desc,lp(-1,-2));
                tile.setOnClickListener(v->chooseTool(tool));
            }
            target.addView(gap(11));
        }
    }

    private void privacyCard(LinearLayout c) {
        LinearLayout box=horizontal(); inset(box,17,18,15,18);
        box.setBackground(outlined(panel(),19,border()));
        c.addView(box,lp(-1,-2));
        TextView shield=text("✓",22,true,0xFF119E83);
        shield.setBackground(shape(dark?0xFF183D39:0xFFDBF5ED,14));
        shield.setGravity(Gravity.CENTER);
        box.addView(shield,lp(46,46));
        LinearLayout copy=vertical(); inset(copy,13,0,0,0);
        box.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        copy.addView(label(R.string.privacy_header,15,true,ink()));
        copy.addView(gap(4));
        copy.addView(label(R.string.privacy_desc,12,false,secondary()));
    }

    private void recentPage(LinearLayout c) {
        c.addView(label(R.string.recent_files,27,true,ink()));
        c.addView(gap(18));
        addRecent(c,false);
        c.addView(gap(20));
        TextView clear=label(R.string.clear_recent,14,true,accent());
        clear.setGravity(Gravity.CENTER);
        clear.setBackground(outlined(panel(),14,border()));
        c.addView(clear,lp(-1,48));
        clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(R.string.clear_recent)
            .setMessage(R.string.clear_recent_confirm).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.confirm,(d,w)->{
                preferences.edit().remove("recent").apply(); draw();
            }).show());
    }

    private void addRecent(LinearLayout c, boolean compact) {
        JSONArray history=loadRecent();
        int count=compact?Math.min(3,history.length()):history.length();
        if(count==0) {
            LinearLayout empty=vertical(); inset(empty,18,24,18,24);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(outlined(panel(),20,border()));
            c.addView(empty,lp(-1,-2));
            empty.addView(label(R.string.no_recent,15,true,ink()));
            empty.addView(gap(7));
            TextView detail=label(R.string.no_recent_hint,12,false,secondary());
            detail.setGravity(Gravity.CENTER); empty.addView(detail);
            return;
        }
        for(int i=0;i<count;i++) {
            JSONObject item=history.optJSONObject(i);
            if(item==null) continue;
            String name=item.optString("name","Document.pdf"), uri=item.optString("uri","");
            LinearLayout line=horizontal(); inset(line,14,14,14,14);
            line.setBackground(outlined(panel(),16,border()));
            c.addView(line,lp(-1,-2));
            TextView icon=text("PDF",11,true,accent());
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(shape(dark?0xFF30334A:0xFFEEEAFF,12));
            line.addView(icon,lp(46,48));
            LinearLayout names=vertical(); inset(names,12,0,7,0);
            line.addView(names,new LinearLayout.LayoutParams(0,-2,1f));
            TextView title=text(name,14,true,ink()); title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            names.addView(title);
            names.addView(gap(4));
            names.addView(label(R.string.offline,11,false,secondary()));
            TextView arrow=text("↗",20,true,accent()); arrow.setGravity(Gravity.CENTER);
            line.addView(arrow,lp(26,36));
            line.setOnClickListener(v->openRecent(Uri.parse(uri), name));
            c.addView(gap(9));
        }
    }

    private JSONArray loadRecent() {
        try { return new JSONArray(preferences.getString("recent","[]")); }
        catch(Exception e){ return new JSONArray(); }
    }
    private void remember(Uri uri) {
        String name=fileName(uri);
        JSONArray old=loadRecent(), next=new JSONArray();
        try {
            JSONObject entry = new JSONObject();
            entry.put("uri",uri.toString()); entry.put("name",name);
            next.put(entry);
            for(int i=0;i<old.length() && next.length()<20;i++) {
                JSONObject prev=old.optJSONObject(i);
                if(prev!=null && !uri.toString().equals(prev.optString("uri"))) next.put(prev);
            }
            preferences.edit().putString("recent",next.toString()).apply();
        } catch(Exception ignored) {}
    }

    private void settingsPage(LinearLayout c) {
        c.addView(label(R.string.settings,27,true,ink()));
        c.addView(gap(20));
        LinearLayout tile=horizontal(); inset(tile,18,18,18,18);
        tile.setBackground(outlined(panel(),18,border()));
        c.addView(tile,lp(-1,-2));
        LinearLayout labels=vertical(); tile.addView(labels,new LinearLayout.LayoutParams(0,-2,1f));
        labels.addView(label(R.string.appearance,16,true,ink()));
        labels.addView(gap(4));
        labels.addView(label(R.string.dark_mode,13,false,secondary()));
        Switch toggle=new Switch(this); toggle.setChecked(dark);
        tile.addView(toggle,lp(-2,-2));
        toggle.setOnCheckedChangeListener((button,on)->{
            dark=on; preferences.edit().putBoolean("dark",on).apply(); draw();
        });
        c.addView(gap(13));
        LinearLayout language=vertical(); inset(language,18,18,18,18);
        language.setBackground(outlined(panel(),18,border())); c.addView(language,lp(-1,-2));
        language.addView(label(R.string.language,16,true,ink()));
        language.addView(gap(5));
        String currentLanguage=LanguageHelper.selected(this);
        TextView languageCurrent=text(LanguageHelper.displayName(this,currentLanguage)+"  ▾",15,true,accent());
        language.addView(languageCurrent,lp(-1,-2));
        language.addView(gap(7));
        language.addView(label(R.string.language_hint,12,false,secondary()));
        language.setOnClickListener(v->chooseLanguage());
        c.addView(gap(13));
        premiumTeaser(c);
        c.addView(gap(13));
        LinearLayout about=vertical(); inset(about,18,18,18,18);
        about.setBackground(outlined(panel(),18,border())); c.addView(about,lp(-1,-2));
        about.addView(label(R.string.about,16,true,ink()));
        about.addView(gap(8)); about.addView(label(R.string.about_desc,13,false,secondary()));
        about.addView(gap(8)); about.addView(text(getString(R.string.version_number,"1.2.0"),12,false,secondary()));
        c.addView(gap(18));
        TextView note=label(R.string.disclaimer,12,false,secondary());
        note.setLineSpacing(dp(3),1f); c.addView(note);
    }

    private void chooseLanguage(){
        String[] codes=LanguageHelper.TAGS;
        String[] names=new String[codes.length];
        String current=LanguageHelper.selected(this);
        int active=0;
        for(int i=0;i<codes.length;i++){
            names[i]=LanguageHelper.displayName(this,codes[i]);
            if(codes[i].equalsIgnoreCase(current))active=i;
        }
        new AlertDialog.Builder(this).setTitle(R.string.language)
            .setSingleChoiceItems(names,active,(dialog,choice)->{
                String code=codes[choice];
                dialog.dismiss();
                LanguageHelper.select(this,code);
                recreate();
            })
            .setNegativeButton(R.string.cancel,null).show();
    }

    private void premiumTeaser(LinearLayout parent){
        LinearLayout row=horizontal();
        inset(row,18,17,16,17);
        row.setBackground(outlined(panel(),19,border()));
        parent.addView(row,lp(-1,-2));
        TextView symbol=text("✦",23,true,0xFF8758F3);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(shape(dark?0xFF343054:0xFFF0EAFF,14));
        row.addView(symbol,lp(48,48));
        LinearLayout titles=vertical();inset(titles,13,0,0,0);
        row.addView(titles,new LinearLayout.LayoutParams(0,-2,1f));
        titles.addView(label(billing.isPremium()?R.string.premium_active:R.string.premium_title,16,true,ink()));
        titles.addView(gap(4));
        titles.addView(label(billing.isPremium()?R.string.premium_active_desc:R.string.premium_teaser,12,false,secondary()));
        TextView arrow=text("→",24,true,accent());
        arrow.setGravity(Gravity.CENTER);row.addView(arrow,lp(28,42));
        row.setOnClickListener(v->{tab=4;draw();});
    }

    private void premiumPage(LinearLayout c){
        c.addView(label(R.string.premium_title,28,true,ink()));
        c.addView(gap(12));
        LinearLayout hero=vertical();inset(hero,24,24,24,23);
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF27214D,0xFF6954D8,0xFF886DFF});
        gradient.setCornerRadius(dp(23));hero.setBackground(gradient);
        c.addView(hero,lp(-1,-2));
        hero.addView(text("✦ PDFForge PRO",25,true,Color.WHITE));
        hero.addView(gap(11));
        hero.addView(label(R.string.premium_subtitle,15,false,Color.WHITE));
        hero.addView(gap(12));
        hero.addView(label(R.string.premium_lifetime,13,true,0xFFEAE4FF));
        c.addView(gap(19));
        String[] benefits=getString(R.string.premium_benefits).split("\\|");
        for(String benefit:benefits){
            LinearLayout item=horizontal();inset(item,14,12,14,12);
            item.setBackground(outlined(panel(),13,border()));
            c.addView(item,lp(-1,-2));
            TextView tick=text("✓",18,true,0xFF119E83);item.addView(tick,lp(27,26));
            TextView value=text(benefit,14,true,ink());
            item.addView(value,new LinearLayout.LayoutParams(0,-2,1));
            c.addView(gap(7));
        }
        c.addView(gap(14));
        if(billing.isPremium()){
            TextView active=label(R.string.premium_active,17,true,0xFF10A980);
            active.setGravity(Gravity.CENTER);
            active.setBackground(outlined(panel(),15,border()));
            c.addView(active,lp(-1,56));
        }else{
            String price=billing.price();
            String buyText=price==null?getString(R.string.premium_not_available)
                    :getString(R.string.premium_buy)+" · "+price;
            TextView buy=text(buyText,16,true,Color.WHITE);
            buy.setGravity(Gravity.CENTER);
            buy.setBackground(shape(0xFF6954E8,15));
            buy.setAlpha(billing.canBuy()?1f:0.55f);
            c.addView(buy,lp(-1,54));
            buy.setOnClickListener(v->billing.purchase());
        }
        c.addView(gap(11));
        TextView restore=label(R.string.premium_restore,14,true,accent());
        restore.setGravity(Gravity.CENTER);
        c.addView(restore,lp(-1,45));
        restore.setOnClickListener(v->billing.restore());
        c.addView(gap(12));
        TextView detail=label(R.string.premium_play_notice,12,false,secondary());
        detail.setLineSpacing(dp(3),1f);
        c.addView(detail);
    }

    private void bottomNavigation(LinearLayout root) {
        LinearLayout nav=horizontal(); inset(nav,8,10,8,8);
        nav.setBackground(outlined(panel(),0,border()));
        root.addView(nav,lp(-1,70));
        int[] names={R.string.home,R.string.tools,R.string.recent,R.string.settings};
        String[] icons={"⌂","◈","▤","⚙"};
        for(int i=0;i<4;i++) {
            final int chosen=i;
            LinearLayout cell=vertical(); cell.setGravity(Gravity.CENTER);
            nav.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
            TextView icon=text(icons[i],23,i==tab, i==tab?accent():secondary());
            icon.setGravity(Gravity.CENTER);
            cell.addView(icon,lp(-1,29));
            cell.addView(gap(3));
            TextView name=label(names[i],11,i==tab,i==tab?accent():secondary());
            name.setGravity(Gravity.CENTER); cell.addView(name,lp(-1,17));
            cell.setOnClickListener(v->{tab=chosen;draw();});
        }
    }

    private void chooseTool(Tool tool) {
        if(tool.isPremium() && !billing.isPremium()){
            new AlertDialog.Builder(this)
                .setTitle(R.string.premium_title)
                .setMessage(R.string.premium_locked)
                .setNegativeButton(R.string.cancel,null)
                .setPositiveButton(R.string.premium_details,(dialog,which)->{tab=4;draw();})
                .show();
            return;
        }
        pending=tool; selected.clear(); parameter="";
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if(tool.officeAction!=null) {
            String mime;
            switch(tool.officeAction){
                case DOCX_PDF: case DOCX_TXT:
                    mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";break;
                case PDF_DOCX: mime="application/pdf";break;
                case XLSX_PDF:case XLSX_CSV:
                    mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";break;
                case PPTX_PDF:
                    mime="application/vnd.openxmlformats-officedocument.presentationml.presentation";break;
                case TXT_PDF: mime="text/plain";break;
                case CSV_PDF:case CSV_XLSX: mime="text/*";break;
                default: mime="*/*";
            }
            intent.setType(mime);
        } else {
            intent.setType(tool.action==PdfEngine.Action.IMAGE_TO_PDF?"image/*":"application/pdf");
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
            tool.action==PdfEngine.Action.MERGE || tool.action==PdfEngine.Action.IMAGE_TO_PDF);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent,PICK); }
        catch(Exception e) { error(getString(R.string.action_failed)+"\n"+e.getMessage()); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null) return;
        if(requestCode==PICK) {
            selected.clear();
            ClipData clip=data.getClipData();
            if(clip!=null) {
                for(int i=0;i<clip.getItemCount();i++) selected.add(clip.getItemAt(i).getUri());
            } else if(data.getData()!=null) {
                selected.add(data.getData());
            }
            if(selected.isEmpty() || pending==null) return;
            for(Uri uri:selected) {
                try {getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                catch(Exception ignored) {}
            }
            if(pending.action==null && pending.officeAction==null) {Uri uri=selected.get(0);remember(uri);openPdf(uri);draw();return;}
            if(pending.action==PdfEngine.Action.MERGE && selected.size()<2) {
                error(getString(R.string.file_too_many));return;
            }
            if(pending.action==PdfEngine.Action.SIGN) { requestSignature(); return; }
            if(pending.action==PdfEngine.Action.EXTRACT || pending.action==PdfEngine.Action.ROTATE) {
                requestRange();return;
            }
            createDestination();
        } else if(requestCode==CREATE && data.getData()!=null) {
            Uri destination=data.getData();
            try {getContentResolver().takePersistableUriPermission(destination,Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
            catch(Exception ignored){}
            execute(destination);
        }
    }


    /** Draw-on-screen e-signature pad, exported as a temporary transparent PNG. */
    private static final class SignaturePad extends View {
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path strokes = new Path();
        private boolean hasInk;
        SignaturePad(Activity activity) {
            super(activity);
            pen.setColor(Color.rgb(38, 35, 74));
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(5f);
            pen.setStrokeCap(Paint.Cap.ROUND);
            pen.setStrokeJoin(Paint.Join.ROUND);
            setBackgroundColor(Color.WHITE);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(strokes, pen);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: strokes.moveTo(x,y); return true;
                case MotionEvent.ACTION_MOVE: strokes.lineTo(x,y); hasInk=true; invalidate(); return true;
                case MotionEvent.ACTION_UP: strokes.lineTo(x,y); hasInk=true;invalidate();return true;
            }
            return true;
        }
        void clear() {strokes.reset();hasInk=false;invalidate();}
        boolean signed() {return hasInk;}
        Bitmap signature() {
            Bitmap bmp = Bitmap.createBitmap(1000, 320, Bitmap.Config.ARGB_8888);
            Canvas image = new Canvas(bmp);
            image.scale(1000f / Math.max(1,getWidth()), 320f / Math.max(1,getHeight()));
            image.drawPath(strokes, pen);
            return bmp;
        }
    }

    private void requestSignature() {
        SignaturePad pad = new SignaturePad(this);
        LinearLayout body = vertical();
        inset(body,20,12,20,4);
        body.addView(pad,lp(-1,200));
        body.addView(gap(12));
        TextView info = label(R.string.sign_note,12,false,secondary());
        body.addView(info,lp(-1,-2));
        AlertDialog alert = new AlertDialog.Builder(this)
            .setTitle(R.string.sign_title).setView(body)
            .setNegativeButton(R.string.cancel,null)
            .setNeutralButton(R.string.clear_signature,(d,w)->{})
            .setPositiveButton(R.string.sign,(d,w)->{})
            .create();
        alert.setOnShowListener(d->{
            alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->pad.clear());
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(!pad.signed()) {Toast.makeText(this,R.string.sign_empty,Toast.LENGTH_SHORT).show();return;}
                try {
                    File file=File.createTempFile("pdf_signature_",".png",getCacheDir());
                    Bitmap bmp=pad.signature();
                    try(FileOutputStream stream=new FileOutputStream(file)) {
                        if(!bmp.compress(Bitmap.CompressFormat.PNG,100,stream)) {
                            throw new java.io.IOException("Could not encode signature.");
                        }
                    } finally {bmp.recycle();}
                    parameter=file.getAbsolutePath();
                    alert.dismiss();
                    createDestination();
                } catch(Exception ex) {error(getString(R.string.action_failed)+"\\n"+ex.getMessage());}
            });
        });
        alert.show();
    }

    private void requestRange() {
        boolean rotate=pending.action==PdfEngine.Action.ROTATE;
        EditText input=new EditText(this);
        input.setText(rotate?"all":"1");
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setHint(rotate?R.string.rotate_hint:R.string.range_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout holder=vertical(); inset(holder,22,8,22,0); holder.addView(input,lp(-1,-2));
        new AlertDialog.Builder(this)
            .setTitle(rotate?R.string.rotate_title:R.string.range_title)
            .setMessage(rotate?R.string.rotate_hint:R.string.range_hint)
            .setView(holder)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.confirm,(d,w)->{
                parameter=input.getText().toString().trim();
                if(parameter.isEmpty()&&!rotate) {error(getString(R.string.range_error));return;}
                createDestination();
            }).show();
    }

    private void createDestination() {
        if(pending.officeAction!=null){ createOfficeDestination();return; }
        String extension="pdf", mime="application/pdf", name="PDFForge";
        if(pending.action==PdfEngine.Action.SPLIT || pending.action==PdfEngine.Action.PDF_TO_IMAGE) {
            extension="zip";mime="application/zip";
        } else if(pending.action==PdfEngine.Action.PDF_TO_TEXT) {
            extension="txt";mime="text/plain";
        }
        switch(pending.action) {
            case MERGE: name="Merged"; break;
            case SPLIT: name="Split_pages"; break;
            case EXTRACT: name="Extracted"; break;
            case ROTATE: name="Rotated"; break;
            case IMAGE_TO_PDF: name="Images"; break;
            case PDF_TO_IMAGE: name="PDF_images"; break;
            case PDF_TO_TEXT: name="Extracted_text"; break;
            case SIGN: name="Signed"; break;
        }
        String time=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new java.util.Date());
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType(mime);
        save.putExtra(Intent.EXTRA_TITLE,"PDFForge_"+name+"_"+time+"."+extension);
        save.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {startActivityForResult(save,CREATE);}
        catch(Exception e) {error(getString(R.string.action_failed)+"\n"+e.getMessage());}
    }

    private void createOfficeDestination(){
        String ext="pdf",mime="application/pdf",name="Converted";
        switch(pending.officeAction){
            case DOCX_PDF:name="Word_to_PDF";break;
            case PDF_DOCX:name="PDF_to_Word";ext="docx";mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";break;
            case DOCX_TXT:name="Word_to_Text";ext="txt";mime="text/plain";break;
            case TXT_PDF:name="Text_to_PDF";break;
            case XLSX_PDF:name="Excel_to_PDF";break;
            case XLSX_CSV:name="Excel_to_CSV";ext="csv";mime="text/csv";break;
            case CSV_XLSX:name="CSV_to_Excel";ext="xlsx";mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";break;
            case CSV_PDF:name="CSV_to_PDF";break;
            case PPTX_PDF:name="PowerPoint_to_PDF";break;
        }
        String time=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new java.util.Date());
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType(mime);
        save.putExtra(Intent.EXTRA_TITLE,"PDFForge_"+name+"_"+time+"."+ext);
        save.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{startActivityForResult(save,CREATE);}
        catch(Exception e){error(getString(R.string.action_failed)+"\n"+e.getMessage());}
    }

    private void execute(Uri output) {
        if(pending.isPremium()&&!billing.isPremium()){
            tab=4;draw();
            Toast.makeText(this,R.string.premium_locked,Toast.LENGTH_LONG).show();
            return;
        }
        PdfEngine.Action action=pending.action;
        OfficeEngine.Action officeAction=pending.officeAction;
        List<Uri> sources=new ArrayList<>(selected);
        String param=parameter;
        LinearLayout holder=horizontal(); inset(holder,22,18,20,18);
        ProgressBar spinner=new ProgressBar(this); holder.addView(spinner,lp(34,34));
        TextView label=label(R.string.action_processing,15,true,ink()); inset(label,13,0,0,0);
        holder.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        AlertDialog busy=new AlertDialog.Builder(this).setView(holder).setCancelable(false).create();
        busy.show();
        worker.execute(()->{
            Throwable problem=null;
            try {
                if(officeAction!=null)
                    OfficeEngine.execute(getApplicationContext(),officeAction,sources.get(0),output);
                else PdfEngine.execute(getApplicationContext(),action,sources,param,output);
            }
            catch(Throwable ex) {problem=ex;}
            Throwable failure=problem;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()) return;
                busy.dismiss();
                if(failure==null) {
                    remember(output);draw();
                    Toast.makeText(this,R.string.action_success,Toast.LENGTH_LONG).show();
                    new AlertDialog.Builder(this).setTitle(R.string.done)
                        .setMessage(R.string.action_success)
                        .setNegativeButton(R.string.done,null)
                        .setPositiveButton(R.string.share,(dialog,which)->shareDocument(output)).show();
                } else {
                    String msg=failure.getMessage();
                    if(msg==null || msg.isEmpty()) msg=getString(R.string.file_unsupported);
                    error(getString(R.string.action_failed)+"\n\n"+msg);
                }
            });
        });
    }

    private String fileName(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null&&c.moveToFirst()) {
                int index=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(index>=0) return c.getString(index);
            }
        } catch(Exception ignored){}
        String last=uri.getLastPathSegment();
        return last==null?"Document.pdf":last;
    }

    private void openRecent(Uri uri,String fileName){
        if(fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")){openPdf(uri);return;}
        Intent view=new Intent(Intent.ACTION_VIEW);
        String mime="*/*";
        String name=fileName.toLowerCase(Locale.ROOT);
        if(name.endsWith(".docx"))mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        else if(name.endsWith(".xlsx"))mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        else if(name.endsWith(".txt"))mime="text/plain";
        else if(name.endsWith(".csv"))mime="text/csv";
        else if(name.endsWith(".zip"))mime="application/zip";
        view.setDataAndType(uri,mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(view);}catch(Exception ex){shareDocument(uri);}
    }

    private void openPdf(Uri uri) {
        Intent view=new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri,"application/pdf");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {startActivity(view);} catch(ActivityNotFoundException e) {
            error(getString(R.string.no_pdf_reader));
        } catch(Exception e) {error(getString(R.string.file_unsupported));}
    }

    private void shareDocument(Uri uri) {
        Intent share=new Intent(Intent.ACTION_SEND);
        String fileName=fileName(uri).toLowerCase(Locale.ROOT);
        String type="application/pdf";
        if(fileName.endsWith(".zip"))type="application/zip";
        else if(fileName.endsWith(".txt"))type="text/plain";
        else if(fileName.endsWith(".csv"))type="text/csv";
        else if(fileName.endsWith(".docx"))type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        else if(fileName.endsWith(".xlsx"))type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        share.setType(type);
        share.putExtra(Intent.EXTRA_STREAM,uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(Intent.createChooser(share,getString(R.string.share)));}
        catch(Exception e) {error(getString(R.string.action_failed));}
    }

    private void error(String message) {
        new AlertDialog.Builder(this).setTitle(R.string.action_failed)
            .setMessage(message).setPositiveButton(R.string.done,null).show();
    }

    @Override protected void onResume(){
        super.onResume();
        if(billing!=null)billing.refresh();
    }

    @Override protected void onDestroy() {
        if(billing!=null)billing.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}

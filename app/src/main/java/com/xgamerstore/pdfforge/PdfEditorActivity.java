package com.xgamerstore.pdfforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in PDF renderer (free) and offline document-annotation editor (Premium).
 * Edits are overlay additions; rewriting existing PDF text is not supported.
 * Changes are only committed to a NEW document using Android SAF.
 */
public class PdfEditorActivity extends Activity {
    private static final int SAVE_REQUEST=654;
    private static final int PURPLE=0xFF6955EE, DARK=0xFF1C2034;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<Integer,List<Mark>> annotations=new HashMap<>();
    private final Map<Integer,Integer> rotations=new HashMap<>();
    private final Set<Integer> deleted=new HashSet<>();
    private BillingManager billing;
    private File localFile;
    private PdfRenderer renderer;
    private ParcelFileDescriptor sourceFd;
    private Bitmap displayed;
    private EditorView canvasView;
    private LinearLayout layout;
    private TextView status;
    private TextView pageLabel;
    private int count, pageIndex;
    private boolean editing, busy;
    private Mode mode=Mode.VIEW;
    private String draftText="";
    private final float[] pageZoom={1f};
    private enum Mode {VIEW,PEN,HIGHLIGHT,TEXT}

    static final class Mark {
        enum Kind {INK,HIGHLIGHT,TEXT}
        Kind kind;
        final List<PointF> points=new ArrayList<>();
        String text;
        Mark(Kind kind){this.kind=kind;}
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF7F7FD);
        getWindow().setNavigationBarColor(0xFFF7F7FD);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        PDFBoxResourceLoader.init(getApplicationContext());
        billing=new BillingManager(this,new BillingManager.Listener(){
            @Override public void changed(){
                if(!hasTestAccess() && editing){editing=false;mode=Mode.VIEW;}
                if(!isFinishing()&&!busy)drawUI();
            }
            @Override public void message(int res){
                Toast.makeText(PdfEditorActivity.this,res,Toast.LENGTH_LONG).show();
            }
        });
        billing.connect();
        drawUI();
        Uri input=getIntent().getData();
        if(input==null){failure(getString(R.string.viewer_failed));return;}
        busy=true;
        worker.execute(()->{
            try{
                File target=File.createTempFile("pdfforge_view_",".pdf",getCacheDir());
                try(InputStream in=getContentResolver().openInputStream(input);
                    OutputStream out=new FileOutputStream(target)){
                    if(in==null)throw new IOException("Unable to access PDF.");
                    byte[] buffer=new byte[32768];int amount;
                    long total=0;
                    while((amount=in.read(buffer))!=-1){
                        total+=amount;
                        if(total>180*1024*1024)throw new IOException("PDF too large to preview (180 MB).");
                        out.write(buffer,0,amount);
                    }
                }
                localFile=target;
                sourceFd=ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_READ_ONLY);
                renderer=new PdfRenderer(sourceFd);
                count=renderer.getPageCount();
                if(count<=0)throw new IOException("Empty PDF.");
                runOnUiThread(()->{
                    busy=false;
                    pageIndex=0;
                    renderPage();
                });
            }catch(Exception ex){
                runOnUiThread(()->{
                    busy=false;
                    failure(getString(R.string.viewer_failed)+"\n"+ex.getMessage());
                });
            }
        });
    }

    @Override protected void onResume(){
        super.onResume();
        if(billing!=null)billing.refresh();
    }

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }
    private TextView label(String title,int size,int color,boolean bold){
        TextView text=new TextView(this);
        text.setText(title);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)text.setTypeface(null,android.graphics.Typeface.BOLD);
        return text;
    }
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout horizontal(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout.LayoutParams size(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void space(LinearLayout c,int h){
        View v=new View(this);c.addView(v,size(1,h));
    }

    private void drawUI(){
        if(isFinishing())return;
        layout=vertical();layout.setBackgroundColor(0xFFF5F6FB);layout.setFitsSystemWindows(true);
        setContentView(layout);
        LinearLayout head=horizontal();head.setPadding(dp(16),dp(12),dp(15),dp(12));
        layout.addView(head,size(-1,68));
        TextView back=label("‹",32,DARK,true);back.setGravity(Gravity.CENTER);
        head.addView(back,size(36,-1));
        back.setOnClickListener(v->finish());
        LinearLayout headings=vertical();
        headings.setPadding(dp(11),0,0,0);
        head.addView(headings,new LinearLayout.LayoutParams(0,-2,1));
        headings.addView(label(getString(R.string.viewer_title),19,DARK,true));
        status=label(editing?getString(R.string.viewer_premium):
            getString(R.string.viewer_readonly),12,0xFF747A8E,false);
        headings.addView(status);
        TextView edit=label(editing?getString(R.string.viewer_cancel_mode):
                getString(R.string.viewer_edit),13,PURPLE,true);
        edit.setGravity(Gravity.CENTER);
        edit.setBackground(background(Color.WHITE,13));
        edit.setPadding(dp(11),0,dp(11),0);
        head.addView(edit,size(-2,42));
        edit.setOnClickListener(v->{
            if(editing){editing=false;mode=Mode.VIEW;drawUI();return;}
            if(!hasTestAccess()){upgrade();return;}
            editing=true;mode=Mode.VIEW;drawUI();
        });
        if(busy){
            ProgressBar progress=new ProgressBar(this);
            layout.addView(progress,new LinearLayout.LayoutParams(-1,0,1));
        }else{
            canvasView=new EditorView();
            canvasView.setBackground(background(Color.WHITE,15));
            LinearLayout.LayoutParams canvasSize=new LinearLayout.LayoutParams(-1,0,1);
            canvasSize.setMargins(dp(12),dp(4),dp(12),dp(8));
            layout.addView(canvasView,canvasSize);
            canvasView.setPage(displayed,pageIndex);
        }
        LinearLayout pageNav=horizontal();
        pageNav.setPadding(dp(15),0,dp(15),0);
        layout.addView(pageNav,size(-1,48));
        pill(pageNav,"‹",()->next(-1),55);
        pageLabel=label(count<=0?"–":getString(R.string.viewer_page,pageIndex+1,count),
                14,DARK,true);
        pageLabel.setGravity(Gravity.CENTER);
        pageNav.addView(pageLabel,new LinearLayout.LayoutParams(0,-1,1));
        pill(pageNav,"−",()->zoom(-0.25f),43);
        pill(pageNav,"+",()->zoom(0.25f),43);
        pill(pageNav,"›",()->next(1),55);
        if(editing && hasTestAccess() && !busy){
            editToolbar();
        }else{
            LinearLayout info=vertical();
            info.setPadding(dp(16),dp(10),dp(16),dp(14));
            layout.addView(info,size(-1,-2));
            TextView hint=label(getString(R.string.viewer_edit_help),12,0xFF69738A,false);
            hint.setGravity(Gravity.CENTER);
            info.addView(hint);
            space(info,8);
            if(!hasTestAccess()){
                TextView unlock=label(getString(R.string.viewer_unlock)+" ✦",15,Color.WHITE,true);
                unlock.setGravity(Gravity.CENTER);
                unlock.setBackground(background(PURPLE,15));
                info.addView(unlock,size(-1,49));
                unlock.setOnClickListener(v->upgrade());
            }
        }
    }

    private void pill(LinearLayout parent,String name,Runnable run,int width){
        TextView button=label(name,23,PURPLE,true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(background(Color.WHITE,12));
        LinearLayout.LayoutParams p=size(width,40);
        p.setMargins(dp(2),0,dp(2),0);
        parent.addView(button,p);
        button.setOnClickListener(v->run.run());
    }
    private void editToolbar(){
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        layout.addView(scroll,size(-1,59));
        LinearLayout bar=horizontal();bar.setPadding(dp(10),dp(3),dp(10),dp(3));
        scroll.addView(bar);
        action(bar,R.string.viewer_pen,"✎",()->setMode(Mode.PEN));
        action(bar,R.string.viewer_highlight,"▧",()->setMode(Mode.HIGHLIGHT));
        action(bar,R.string.viewer_text,"T",this::promptText);
        action(bar,R.string.viewer_undo,"↶",this::undo);
        action(bar,R.string.viewer_rotate,"↻",this::rotate);
        action(bar,deleted.contains(pageIndex)?R.string.viewer_restore:R.string.viewer_delete,
                "⊖",this::deleteOrRestore);
        LinearLayout footer=horizontal();
        footer.setPadding(dp(15),dp(8),dp(15),dp(11));
        layout.addView(footer,size(-1,65));
        TextView save=label(getString(R.string.viewer_save)+"  →",16,Color.WHITE,true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(background(PURPLE,14));
        footer.addView(save,size(-1,49));
        save.setOnClickListener(v->save());
    }
    private void action(LinearLayout bar,int id,String symbol,Runnable run){
        TextView button=label(symbol+"  "+getString(id),13,
                (id==R.string.viewer_pen&&mode==Mode.PEN)
                ||(id==R.string.viewer_highlight&&mode==Mode.HIGHLIGHT)?PURPLE:DARK,true);
        button.setBackground(background(Color.WHITE,12));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12),0,dp(12),0);
        LinearLayout.LayoutParams params=size(-2,45);params.setMargins(dp(3),0,dp(3),0);
        bar.addView(button,params);
        button.setOnClickListener(v->run.run());
    }
    private void setMode(Mode newMode){
        mode=mode==newMode?Mode.VIEW:newMode;
        drawUI();
        if(canvasView!=null)canvasView.invalidate();
    }
    private void promptText(){
        EditText input=new EditText(this);
        input.setSingleLine(false);input.setMinLines(2);
        input.setHint(R.string.viewer_text_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout wrapper=vertical();
        wrapper.setPadding(dp(20),0,dp(20),0);
        wrapper.addView(input);
        new AlertDialog.Builder(this).setTitle(R.string.viewer_text)
            .setView(wrapper).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.confirm,(dialog,which)->{
                draftText=input.getText().toString().trim();
                if(!draftText.isEmpty()){
                    mode=Mode.TEXT;
                    Toast.makeText(this,R.string.viewer_tap_text,Toast.LENGTH_LONG).show();
                    drawUI();
                }
            }).show();
    }
    private void next(int direction){
        if(busy||renderer==null)return;
        int target=Math.max(0,Math.min(count-1,pageIndex+direction));
        if(target==pageIndex)return;
        pageIndex=target;mode=Mode.VIEW;pageZoom[0]=1f;
        renderPage();
    }
    private void zoom(float change){
        pageZoom[0]=Math.max(1f,Math.min(4f,pageZoom[0]+change));
        if(canvasView!=null){canvasView.resetPan();canvasView.invalidate();}
    }
    private void renderPage(){
        if(renderer==null || pageIndex>=count || busy)return;
        try(PdfRenderer.Page page=renderer.openPage(pageIndex)){
            int w=page.getWidth(), h=page.getHeight();
            float scale=Math.min(2.15f,Math.min(2000f/Math.max(1,w),2200f/Math.max(1,h)));
            int outputW=Math.max(1,Math.round(w*scale));
            int outputH=Math.max(1,Math.round(h*scale));
            Bitmap bitmap=Bitmap.createBitmap(outputW,outputH,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            int turns=(rotations.containsKey(pageIndex)?rotations.get(pageIndex):0)%360;
            if(turns!=0){
                Matrix matrix=new Matrix();matrix.postRotate(turns);
                Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
                if(bitmap!=rotated)bitmap.recycle();
                bitmap=rotated;
            }
            if(displayed!=null && !displayed.isRecycled())displayed.recycle();
            displayed=bitmap;
            drawUI();
        }catch(Exception e){failure(getString(R.string.viewer_failed)+"\n"+e.getMessage());}
    }

    private List<Mark> marks(int index){
        List<Mark> result=annotations.get(index);
        if(result==null){result=new ArrayList<>();annotations.put(index,result);}
        return result;
    }

    private void undo(){
        List<Mark> list=marks(pageIndex);
        if(!list.isEmpty())list.remove(list.size()-1);
        if(canvasView!=null)canvasView.invalidate();
    }
    private void rotate(){
        int old=rotations.containsKey(pageIndex)?rotations.get(pageIndex):0;
        rotations.put(pageIndex,(old+90)%360);
        for(Mark m:marks(pageIndex)){
            for(PointF p:m.points){float u=p.x;p.x=1f-p.y;p.y=u;}
        }
        renderPage();
    }
    private void deleteOrRestore(){
        if(deleted.contains(pageIndex)){
            deleted.remove(pageIndex);drawUI();return;
        }
        if(count-deleted.size()<=1){
            Toast.makeText(this,R.string.viewer_last_page,Toast.LENGTH_SHORT).show();return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.viewer_delete)
            .setMessage(R.string.viewer_delete_confirm)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.confirm,(d,w)->{
                deleted.add(pageIndex);drawUI();
                Toast.makeText(this,R.string.viewer_page_deleted,Toast.LENGTH_SHORT).show();
            }).show();
    }

    private boolean changed(){
        if(!deleted.isEmpty())return true;
        for(int amount:rotations.values())if(amount!=0)return true;
        for(List<Mark> marks:annotations.values())if(!marks.isEmpty())return true;
        return false;
    }
    private void save(){
        if(!hasTestAccess()){upgrade();return;}
        if(!changed()){Toast.makeText(this,R.string.viewer_nothing,Toast.LENGTH_SHORT).show();return;}
        Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/pdf");
        create.putExtra(Intent.EXTRA_TITLE,"PDFForge_edited_"+System.currentTimeMillis()+".pdf");
        create.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(create,SAVE_REQUEST);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SAVE_REQUEST||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri output=data.getData();
        if(!hasTestAccess()){upgrade();return;}
        busy=true;drawUI();
        worker.execute(()->{
            Throwable problem=null;
            try {
                try(PDDocument document=PDDocument.load(localFile);
                    OutputStream destination=getContentResolver().openOutputStream(output,"w")){
                    if(destination==null)throw new IOException("Cannot save the PDF.");
                    for(int i=0;i<count;i++){
                        if(deleted.contains(i))continue;
                        PDPage page=document.getPage(i);
                        List<Mark> edits=annotations.get(i);
                        int extra=rotations.containsKey(i)?rotations.get(i):0;
                        int totalRotation=(page.getRotation()+extra)%360;
                        if(edits!=null && !edits.isEmpty()){
                            float width=page.getMediaBox().getWidth();
                            float height=page.getMediaBox().getHeight();
                            int orientedW=(totalRotation==90||totalRotation==270)?1000:Math.max(1,Math.round(1000*width/height));
                            int orientedH=(totalRotation==90||totalRotation==270)?Math.max(1,Math.round(1000*width/height)):1000;
                            // Use the document aspect ratio rather than assuming A4.
                            float finalRatio=(totalRotation==90||totalRotation==270)?height/width:width/height;
                            orientedW=Math.max(1,Math.round(1100*Math.min(1f,finalRatio)));
                            orientedH=Math.max(1,Math.round(orientedW/finalRatio));
                            Bitmap overlay=Bitmap.createBitmap(orientedW,orientedH,Bitmap.Config.ARGB_8888);
                            Canvas artwork=new Canvas(overlay);
                            paintMarks(artwork,edits,orientedW,orientedH);
                            Matrix inverse=new Matrix();inverse.postRotate(-totalRotation);
                            Bitmap unrotated=totalRotation==0?overlay:
                                Bitmap.createBitmap(overlay,0,0,overlay.getWidth(),overlay.getHeight(),inverse,true);
                            try{
                                PDImageXObject image=LosslessFactory.createFromImage(document,unrotated);
                                PDRectangle box=page.getMediaBox();
                                try(PDPageContentStream stream=new PDPageContentStream(document,page,
                                        PDPageContentStream.AppendMode.APPEND,true,true)){
                                    stream.drawImage(image,box.getLowerLeftX(),box.getLowerLeftY(),
                                        box.getWidth(),box.getHeight());
                                }
                            }finally {
                                if(unrotated!=overlay)unrotated.recycle();
                                overlay.recycle();
                            }
                        }
                        if(extra!=0)page.setRotation(totalRotation);
                    }
                    for(int i=count-1;i>=0;i--)if(deleted.contains(i))document.removePage(i);
                    document.save(destination);
                }
            }catch(Throwable e){problem=e;}
            final Throwable failure=problem;
            runOnUiThread(()->{
                if(isFinishing())return;
                busy=false;drawUI();
                if(failure==null){
                    Toast.makeText(this,R.string.viewer_saved,Toast.LENGTH_LONG).show();
                    new AlertDialog.Builder(this).setTitle(R.string.viewer_saved)
                        .setPositiveButton(R.string.done,(d,w)->{})
                        .setNeutralButton(R.string.open,(d,w)->{
                            Intent open=new Intent(Intent.ACTION_VIEW);
                            open.setDataAndType(output,"application/pdf");
                            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try{startActivity(open);}catch(Exception ignored){}
                        }).show();
                }else failure(getString(R.string.viewer_save_error)+"\n"+failure.getMessage());
            });
        });
    }

    private static void paintMarks(Canvas canvas,List<Mark> items,float width,float height){
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        for(Mark mark:items){
            if(mark.points.isEmpty())continue;
            if(mark.kind==Mark.Kind.TEXT){
                paint.setColor(0xFF272247);
                paint.setTypeface(android.graphics.Typeface.create("sans-serif",1));
                paint.setTextSize(Math.max(9f,width*0.027f));
                paint.setStyle(Paint.Style.FILL);
                PointF pos=mark.points.get(0);
                String[] lines=(mark.text==null?"":mark.text).split("\n",-1);
                for(int i=0;i<lines.length;i++)
                    canvas.drawText(lines[i],pos.x*width,pos.y*height+i*paint.getTextSize()*1.25f,paint);
            }else{
                paint.setTypeface(null);
                paint.setColor(mark.kind==Mark.Kind.INK?0xFF3B32C0:0x88FFE456);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(width*(mark.kind==Mark.Kind.INK?0.004f:0.026f));
                Path p=new Path();PointF first=mark.points.get(0);
                p.moveTo(first.x*width,first.y*height);
                if(mark.points.size()==1)p.lineTo(first.x*width+0.1f,first.y*height+0.1f);
                for(int i=1;i<mark.points.size();i++){
                    PointF step=mark.points.get(i);p.lineTo(step.x*width,step.y*height);
                }
                canvas.drawPath(p,paint);
            }
        }
    }

    private void upgrade(){
        new AlertDialog.Builder(this).setTitle(R.string.premium_title)
            .setMessage(R.string.premium_locked)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.viewer_unlock,(d,w)->{
                Intent intent=new Intent(this,MainActivity.class);
                intent.putExtra("open_premium",true);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }).show();
    }

    private void failure(String message){
        new AlertDialog.Builder(this).setTitle(R.string.viewer_failed)
            .setMessage(message).setPositiveButton(R.string.done,(d,w)->finish())
            .setCancelable(false).show();
    }

    private final class EditorView extends View {
        private Bitmap pageBitmap;
        private int drawnPage;
        private Mark current;
        private float left, top, width, height;
        private float panX,panY;
        private float touchX,touchY;
        private final ScaleGestureDetector pinch;
        EditorView(){
            super(PdfEditorActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            pinch=new ScaleGestureDetector(PdfEditorActivity.this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                    @Override public boolean onScale(ScaleGestureDetector detector){
                        pageZoom[0]=Math.min(4f,Math.max(1f,pageZoom[0]*detector.getScaleFactor()));
                        invalidate();return true;
                    }
                });
        }
        void setPage(Bitmap page,int number){pageBitmap=page;drawnPage=number;invalidate();}
        void resetPan(){panX=0;panY=0;}
        @Override protected void onDraw(Canvas displayCanvas){
            super.onDraw(displayCanvas);
            displayCanvas.drawColor(0xFFE8EBF2);
            if(pageBitmap==null||pageBitmap.isRecycled())return;
            float scale=Math.min((getWidth()-dp(8))/(float)pageBitmap.getWidth(),
                    (getHeight()-dp(8))/(float)pageBitmap.getHeight())*pageZoom[0];
            width=pageBitmap.getWidth()*scale;height=pageBitmap.getHeight()*scale;
            left=(getWidth()-width)/2f+panX;top=(getHeight()-height)/2f+panY;
            displayCanvas.drawBitmap(pageBitmap,null,new RectF(left,top,left+width,top+height),
                new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
            displayCanvas.save();
            displayCanvas.translate(left,top);
            displayCanvas.scale(width/pageBitmap.getWidth(),height/pageBitmap.getHeight());
            List<Mark> combined=new ArrayList<>(marks(drawnPage));
            if(current!=null && !combined.contains(current))combined.add(current);
            paintMarks(displayCanvas,combined,pageBitmap.getWidth(),pageBitmap.getHeight());
            displayCanvas.restore();
            if(deleted.contains(drawnPage)){
                Paint shade=new Paint(Paint.ANTI_ALIAS_FLAG);
                shade.setColor(0xAADD3344);
                displayCanvas.drawRect(left,top,left+width,top+height,shade);
                shade.setColor(Color.WHITE);
                shade.setTextAlign(Paint.Align.CENTER);
                shade.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                shade.setTextSize(dp(20));
                displayCanvas.drawText(getString(R.string.viewer_page_deleted),
                    getWidth()/2f,getHeight()/2f,shade);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event){
            if(pageBitmap==null)return false;
            pinch.onTouchEvent(event);
            if(event.getPointerCount()>1||pinch.isInProgress())return true;
            float x=event.getX(),y=event.getY();
            if(!editing||mode==Mode.VIEW){
                if(pageZoom[0]>1.01f){
                    if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                        touchX=x;touchY=y;return true;
                    }
                    if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                        panX+=x-touchX;panY+=y-touchY;
                        touchX=x;touchY=y;invalidate();return true;
                    }
                }
                return true;
            }
            if(deleted.contains(drawnPage))return true;
            float u=(x-left)/Math.max(1,width),v=(y-top)/Math.max(1,height);
            if(u<0||u>1||v<0||v>1)return true;
            if(mode==Mode.TEXT && event.getActionMasked()==MotionEvent.ACTION_UP){
                Mark mark=new Mark(Mark.Kind.TEXT);
                mark.text=draftText;mark.points.add(new PointF(u,v));
                marks(drawnPage).add(mark);
                mode=Mode.VIEW;
                drawUI();return true;
            }
            if(mode!=Mode.PEN&&mode!=Mode.HIGHLIGHT)return true;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    current=new Mark(mode==Mode.PEN?Mark.Kind.INK:Mark.Kind.HIGHLIGHT);
                    current.points.add(new PointF(u,v));invalidate();return true;
                case MotionEvent.ACTION_MOVE:
                    if(current!=null){current.points.add(new PointF(u,v));invalidate();}
                    return true;
                case MotionEvent.ACTION_UP:
                    if(current!=null){
                        current.points.add(new PointF(u,v));
                        marks(drawnPage).add(current);current=null;
                        invalidate();
                    }
                    return true;
            }
            return true;
        }
    }

    /** Local developer builds have editing enabled for feature tests. Production uses Play entitlements. */
    private boolean hasTestAccess(){
        boolean development=(getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        return development || billing.isPremium();
    }

    @Override protected void onDestroy(){
        worker.shutdownNow();
        if(billing!=null)billing.close();
        if(displayed!=null&&!displayed.isRecycled())displayed.recycle();
        try{if(renderer!=null)renderer.close();}catch(Exception ignored){}
        try{if(sourceFd!=null)sourceFd.close();}catch(Exception ignored){}
        if(localFile!=null)localFile.delete();
        super.onDestroy();
    }
}

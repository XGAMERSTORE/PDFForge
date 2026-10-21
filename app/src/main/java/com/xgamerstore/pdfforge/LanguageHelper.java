package com.xgamerstore.pdfforge;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** Per-app languages. Android 13+ uses LocaleManager; older devices use context overrides. */
final class LanguageHelper {
    static final String KEY = "language";
    static final String[] TAGS = {
        "system","en","cs","sk","de","fr","es","it","pt","pl","nl","sv",
        "ro","hu","tr","uk","ru","ar","hi","id","vi","ja","ko","zh-CN","zh-TW"
    };
    private LanguageHelper() {}

    static Context wrap(Context base) {
        if(Build.VERSION.SDK_INT >= 33) return base;
        String language = base.getSharedPreferences("pdf_forge", Context.MODE_PRIVATE)
                .getString(KEY, "system");
        if(language == null || "system".equals(language)) return base;
        Locale locale = Locale.forLanguageTag(language);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    static String selected(Context context) {
        if(Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager=context.getSystemService(LocaleManager.class);
            if(manager!=null){
                String tags=manager.getApplicationLocales().toLanguageTags();
                if(tags.isEmpty())return "system";
                String first=tags.split(",")[0];
                for(String tag:TAGS)if(tag.equalsIgnoreCase(first))return tag;
                return first;
            }
        }
        return context.getSharedPreferences("pdf_forge", Context.MODE_PRIVATE)
                .getString(KEY,"system");
    }

    static void select(Context context,String code) {
        context.getSharedPreferences("pdf_forge",Context.MODE_PRIVATE)
                .edit().putString(KEY,code).apply();
        if(Build.VERSION.SDK_INT>=33){
            LocaleManager manager=context.getSystemService(LocaleManager.class);
            if(manager!=null)manager.setApplicationLocales(
                "system".equals(code)?LocaleList.getEmptyLocaleList():LocaleList.forLanguageTags(code));
        }
    }

    static String displayName(Context context,String code) {
        if("system".equals(code))return context.getString(R.string.language_system);
        Locale locale=Locale.forLanguageTag(code);
        String value=locale.getDisplayName(locale);
        return value.isEmpty()?code:Character.toUpperCase(value.charAt(0))+value.substring(1);
    }
}

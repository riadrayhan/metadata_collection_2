package com.datacollector;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Detects installed apps relevant to credit scoring:
 * - Mobile financial services (bKash, Nagad, Rocket, Upay, etc.)
 * - Ride-hailing (Uber, Pathao, Obhai)
 * - E-commerce (Daraz, Evaly, Chaldal, etc.)
 * - Banking apps
 * - Social/communication apps (WhatsApp, Messenger, IMO)
 * - Food delivery (Foodpanda, HungryNaki)
 */
public class InstalledAppsCollector {

    private final Context context;
    private final DatabaseHelper db;

    // Package names of interest for credit scoring
    private static final String[][] TRACKED_APPS = {
        // Mobile Financial Services
        {"com.bKash.customerapp", "bKash", "MFS"},
        {"com.konasl.nagad", "Nagad", "MFS"},
        {"com.dbbl.mbs.apps.main", "Rocket (DBBL)", "MFS"},
        {"bd.com.upay", "Upay", "MFS"},
        {"com.progoti.tallykhata", "TallyKhata", "MFS"},
        {"com.mcash.android", "mCash", "MFS"},
        {"com.tap.android", "Tap", "MFS"},
        {"bd.com.islamibank.cellfin", "CellFin", "MFS"},
        
        // Banking
        {"com.ibl.ebanking.android", "City Bank", "BANKING"},
        {"com.brac.bank.asBankApp", "BRAC Bank", "BANKING"},
        {"com.dutchbangla.nexus", "DBBL Nexus", "BANKING"},
        {"com.ebl.skybanking", "EBL Sky Banking", "BANKING"},
        
        // Ride-hailing
        {"com.ubercab", "Uber", "RIDE"},
        {"com.pathao.user", "Pathao", "RIDE"},
        {"com.obhai.user", "Obhai", "RIDE"},
        
        // E-commerce
        {"com.daraz.android", "Daraz", "ECOMMERCE"},
        {"com.chaldal.poached", "Chaldal", "ECOMMERCE"},
        {"com.pickaboo.app", "Pickaboo", "ECOMMERCE"},
        {"com.othoba.android", "Othoba", "ECOMMERCE"},
        
        // Food Delivery
        {"com.global.foodpanda.android", "Foodpanda", "FOOD_DELIVERY"},
        {"com.pathao.food", "Pathao Food", "FOOD_DELIVERY"},
        {"com.hungrynaki.android", "HungryNaki", "FOOD_DELIVERY"},
        {"com.shohoz.food", "Shohoz Food", "FOOD_DELIVERY"},
        
        // Social & Communication
        {"com.whatsapp", "WhatsApp", "SOCIAL"},
        {"com.facebook.orca", "Messenger", "SOCIAL"},
        {"com.facebook.katana", "Facebook", "SOCIAL"},
        {"com.imo.android.imoim", "IMO", "SOCIAL"},
        {"com.viber.voip", "Viber", "SOCIAL"},
        {"org.telegram.messenger", "Telegram", "SOCIAL"},
        
        // Utility / Productivity
        {"com.google.android.apps.maps", "Google Maps", "UTILITY"},
        {"com.google.android.gm", "Gmail", "UTILITY"},
        {"com.linkedin.android", "LinkedIn", "PROFESSIONAL"},
        
        // Telecom
        {"com.grameenphone.gp", "My GP", "TELECOM"},
        {"com.robi.myrobi", "My Robi", "TELECOM"},
        {"com.banglalink.mybl", "My Banglalink", "TELECOM"},
    };

    public InstalledAppsCollector(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
    }

    public void collect() {
        PackageManager pm = context.getPackageManager();
        String timestamp = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        // Check each tracked app
        for (String[] appInfo : TRACKED_APPS) {
            String packageName = appInfo[0];
            String appName = appInfo[1];
            String category = appInfo[2];

            try {
                PackageInfo pi = pm.getPackageInfo(packageName, 0);
                String version = pi.versionName != null ? pi.versionName : "unknown";
                long installDate = pi.firstInstallTime;
                long lastUpdate = pi.lastUpdateTime;
                String installDateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(new Date(installDate));
                String lastUpdateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(new Date(lastUpdate));

                db.insertInstalledApp(packageName, appName, category, version,
                    installDateStr, lastUpdateStr, "INSTALLED", timestamp);

            } catch (PackageManager.NameNotFoundException e) {
                // App not installed — record as not found (useful signal too)
                db.insertInstalledApp(packageName, appName, category, "",
                    "", "", "NOT_INSTALLED", timestamp);
            }
        }

        // Also count total installed apps
        List<ApplicationInfo> allApps = pm.getInstalledApplications(0);
        int totalApps = 0;
        int systemApps = 0;
        int userApps = 0;
        for (ApplicationInfo appInfo : allApps) {
            totalApps++;
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                systemApps++;
            } else {
                userApps++;
            }
        }

        // Store summary
        db.insertInstalledApp("_summary_", "App Summary", "SUMMARY", "",
            String.valueOf(totalApps),
            "system:" + systemApps + ",user:" + userApps,
            "SUMMARY", timestamp);
    }
}

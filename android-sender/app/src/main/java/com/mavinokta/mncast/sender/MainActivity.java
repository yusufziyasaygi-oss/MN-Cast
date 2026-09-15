package com.mavinokta.mncast.sender;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int CAPTURE = 4101;
    private static final int NOTIFY = 4102;
    private static final int AUDIO = 4103;
    private final Map<String, NsdServiceInfo> devices = new LinkedHashMap<>();
    private LinearLayout deviceList;
    private TextView status;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private NsdServiceInfo selected;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(makeUi());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 4104);
        }
        startDiscovery();
    }

    private View makeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(26), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(9,11,16));

        TextView mark = text("MN", 14, 0xFF8FAAFF); mark.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(mark);
        TextView title = text("MN Cast", 34, Color.WHITE); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1,-2); tp.topMargin=dp(8); root.addView(title,tp);
        TextView sub = text("Android ekranını TV’ye düşük gecikmeyle yansıt", 16, 0xFF9EA6B6);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,-2); sp.topMargin=dp(8); sp.bottomMargin=dp(26); root.addView(sub,sp);

        status = text("Aynı Wi‑Fi ağındaki MN Cast TV’ler aranıyor…", 14, 0xFFBAC1CF);
        root.addView(status);
        deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this); scroll.addView(deviceList);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1,0,1); slp.topMargin=dp(16); root.addView(scroll,slp);

        TextView note = text("Apple cihazları TV’deki MN Cast’a doğrudan AirPlay ile bağlanır. Bu uygulama Android tam ekran yansıtma içindir.", 12, 0xFF737C8F);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1,-2); np.topMargin=dp(14); root.addView(note,np);
        return root;
    }

    private void startDiscovery() {
        nsd = (NsdManager)getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String s){ runOnUiThread(() -> status.setText("MN Cast TV aranıyor…")); }
            public void onServiceFound(NsdServiceInfo info){
                if (!info.getServiceType().contains("_mncast")) return;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo si,int e){}
                    public void onServiceResolved(NsdServiceInfo si){ runOnUiThread(() -> addDevice(si)); }
                });
            }
            public void onServiceLost(NsdServiceInfo info){ runOnUiThread(() -> removeDevice(info.getServiceName())); }
            public void onDiscoveryStopped(String s){}
            public void onStartDiscoveryFailed(String s,int e){ try{nsd.stopServiceDiscovery(this);}catch(Exception ignored){} }
            public void onStopDiscoveryFailed(String s,int e){ try{nsd.stopServiceDiscovery(this);}catch(Exception ignored){} }
        };
        nsd.discoverServices("_mncast._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    private void addDevice(NsdServiceInfo info) {
        devices.put(info.getServiceName(), info);
        renderDevices();
    }
    private void removeDevice(String name) { devices.remove(name); renderDevices(); }

    private void renderDevices() {
        deviceList.removeAllViews();
        if (devices.isEmpty()) { status.setText("Henüz TV bulunamadı. TV’de MN Cast açık olmalı."); return; }
        status.setText(devices.size()+" MN Cast TV bulundu");
        for (NsdServiceInfo d: devices.values()) {
            Button b = new Button(this);
            b.setAllCaps(false); b.setText(d.getServiceName()+"\nBağlan ve ekranı yansıt"); b.setTextSize(16); b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); b.setPadding(dp(18),dp(14),dp(18),dp(14));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF171B24); bg.setCornerRadius(dp(18)); bg.setStroke(dp(1),0xFF2A3242); b.setBackground(bg);
            b.setOnClickListener(v -> beginCapture(d));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(76)); p.bottomMargin=dp(12); deviceList.addView(b,p);
        }
    }

    private void beginCapture(NsdServiceInfo d) {
        selected=d;
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(),CAPTURE);
    }

    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req!=CAPTURE || result!=RESULT_OK || data==null || selected==null) return;
        InetAddress host=selected.getHost(); if(host==null){ status.setText("TV adresi alınamadı."); return; }
        Intent i=new Intent(this,CaptureService.class)
                .putExtra("resultCode",result).putExtra("resultData",data)
                .putExtra("host",host.getHostAddress()).putExtra("port",selected.getPort());
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        status.setText("Yansıtma başladı • durdurmak için bildirimi kullan");
    }

    private TextView text(String s,float size,int color){ TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t; }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void onDestroy(){ try{ if(nsd!=null&&discovery!=null)nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){} super.onDestroy(); }
}

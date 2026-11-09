package com.rimdroid.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.rimdroid.BuildConfig;
import com.rimdroid.LogExporter;
import com.rimdroid.R;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Report a bug" as one screen (as in Zomdroid, 2026-11; it used to fire the mail intent straight
 * from the drawer, and "Export logs" was a second drawer item). One instance picker, then two ways
 * to send the same log bundle ({@link LogExporter}): by email - the list shows mail apps only, the
 * old share sheet offered every app that takes a file and players picked Reddit or Telegram - or
 * saved to a file, for devices with no mail app (handhelds), with the address to send it to.
 */
public class BugReportFragment extends Fragment {

    private Spinner spInstance;
    private Button btnEmail, btnSave;
    private final List<GameInstance> instances = new ArrayList<>();
    private boolean hasChooseRow;

    private final ActivityResultLauncher<String> saveLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (uri != null) saveReport(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bug_report, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_bug_report_instance);
        btnEmail = v.findViewById(R.id.btn_bug_report_email);
        btnSave = v.findViewById(R.id.btn_bug_report_save);
        TextView address = v.findViewById(R.id.tv_bug_report_address);
        address.setText(getString(R.string.bug_report_email));
        v.findViewById(R.id.btn_bug_report_copy).setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("email", getString(R.string.bug_report_email)));
            Toast.makeText(requireContext(), R.string.bug_report_copied, Toast.LENGTH_SHORT).show();
        });

        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        // Several instances: the reporter says which game misbehaved (1.5, 1.6 and 1.7 logs differ).
        // None: the email still goes, without logs - a failed install is a report we need too.
        hasChooseRow = instances.size() > 1;
        if (hasChooseRow) names.add(getString(R.string.bug_report_pick_instance));
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { updateButtons(); }
            @Override public void onNothingSelected(AdapterView<?> p) { updateButtons(); }
        });
        btnEmail.setOnClickListener(x -> sendByEmail());
        btnSave.setOnClickListener(x -> saveLauncher.launch("rimdroid_report_" + timestamp() + ".zip"));
        updateButtons();
    }

    private boolean chosen() {
        return !hasChooseRow || spInstance.getSelectedItemPosition() > 0;
    }

    @Nullable
    private GameInstance selected() {
        if (instances.isEmpty()) return null;
        int i = spInstance.getSelectedItemPosition() - (hasChooseRow ? 1 : 0);
        return i >= 0 && i < instances.size() ? instances.get(i) : null;
    }

    private void updateButtons() {
        boolean ok = chosen();
        btnEmail.setEnabled(ok);
        btnSave.setEnabled(ok && !instances.isEmpty());   // the file is the logs: none without an instance
    }

    private static String timestamp() {
        return new SimpleDateFormat("ddMMyyyy_HHmm", Locale.US).format(new Date());
    }

    private static String device(@Nullable GameInstance instance) {
        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE
                + "\nRimDroid: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + (instance != null ? "\nInstance: " + instance.getName() : "");
    }

    // ---- by email ----

    private void sendByEmail() {
        GameInstance instance = selected();
        Context app = requireContext().getApplicationContext();
        String stamp = timestamp();
        if (instance == null) { startEmail(null, null); return; }
        Toast.makeText(app, R.string.bug_report_preparing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Uri attach = null;
            try {
                // Building the zip can read multi-MB game logs, so it runs off the UI thread.
                File dir = new File(app.getCacheDir(), "reports");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File zip = new File(dir, "rimdroid_report_" + stamp + ".zip");
                    try (OutputStream out = new FileOutputStream(zip)) {
                        LogExporter.export(app, instance, out);
                    }
                    if (zip.length() > 0) attach = FileProvider.getUriForFile(app, "com.rimdroid.fileprovider", zip);
                }
            } catch (Throwable t) {
                attach = null;   // no logs: the text report still goes
            }
            final Uri fAttach = attach;
            android.app.Activity act = getActivity();
            if (act != null) act.runOnUiThread(() -> startEmail(instance, fAttach));
        }, "bug-report").start();
    }

    private void startEmail(@Nullable GameInstance instance, @Nullable Uri attachment) {
        if (!isAdded()) return;
        String[] to = {getString(R.string.bug_report_email)};
        String subject = getString(R.string.bug_report_subject,
                new SimpleDateFormat("ddMMyyyy", Locale.US).format(new Date()));
        // Always English, whatever the launcher's language: it steers players to write the
        // description in English too, so reports need no translating.
        android.content.res.Configuration en = new android.content.res.Configuration(getResources().getConfiguration());
        en.setLocale(Locale.ENGLISH);
        String body = requireContext().createConfigurationContext(en).getString(R.string.bug_report_body, device(instance));
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_EMAIL, to);
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, body);
        if (attachment != null) {
            i.putExtra(Intent.EXTRA_STREAM, attachment);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        // Mail apps only. The system can't be asked for that: the share sheet lists every app that
        // takes a file, and a mailto: selector on ACTION_SEND came up empty on a Galaxy with Gmail
        // and Outlook installed (Zomdroid). So: the apps that handle mailto: (the <queries> entry in
        // the manifest makes them visible) and also take this ACTION_SEND (PayPal handles mailto:
        // but sends nothing), one screen per app, started by component. Mail.ru has a screen that
        // ignores the address and mails the player ("MailToMySelf"): screens named "self" are skipped.
        android.content.pm.PackageManager pm = requireContext().getPackageManager();
        List<android.content.pm.ResolveInfo> mail = pm.queryIntentActivities(
                new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0);
        List<String> packages = new ArrayList<>();
        List<android.content.ComponentName> targets = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        List<android.graphics.drawable.Drawable> icons = new ArrayList<>();
        for (android.content.pm.ResolveInfo r : mail) {
            String pkg = r.activityInfo.packageName;
            if (packages.contains(pkg)) continue;
            android.content.ComponentName target = null;
            for (android.content.pm.ResolveInfo s : pm.queryIntentActivities(new Intent(i).setPackage(pkg), 0)) {
                if (s.activityInfo.name.toLowerCase(Locale.ROOT).contains("self")) continue;
                target = new android.content.ComponentName(pkg, s.activityInfo.name);
                break;
            }
            if (target == null) continue;
            packages.add(pkg);
            targets.add(target);
            labels.add(r.activityInfo.applicationInfo.loadLabel(pm));
            icons.add(r.activityInfo.applicationInfo.loadIcon(pm));
        }
        if (packages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.bug_report_no_mail_use_file, Toast.LENGTH_LONG).show();
            return;
        }
        if (packages.size() == 1) {
            launchMail(i, targets.get(0));
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.bug_report_pick_mail)
                .setAdapter(mailAppsAdapter(labels, icons), (d, w) -> launchMail(i, targets.get(w)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Rows of the mail app list: the app's icon, then its name, like the system's "Open with". */
    private android.widget.ListAdapter mailAppsAdapter(List<CharSequence> labels,
                                                      List<android.graphics.drawable.Drawable> icons) {
        float dp = getResources().getDisplayMetrics().density;
        return new ArrayAdapter<CharSequence>(requireContext(), android.R.layout.simple_list_item_1, labels) {
            @NonNull @Override
            public View getView(int pos, @Nullable View convert, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convert, parent);
                android.graphics.drawable.Drawable d = icons.get(pos);
                int size = Math.round(36 * dp);
                d.setBounds(0, 0, size, size);
                tv.setCompoundDrawablesRelative(d, null, null, null);
                tv.setCompoundDrawablePadding(Math.round(16 * dp));
                tv.setPaddingRelative(Math.round(24 * dp), tv.getPaddingTop(), tv.getPaddingEnd(), tv.getPaddingBottom());
                return tv;
            }
        };
    }

    private void launchMail(Intent send, android.content.ComponentName target) {
        try {
            startActivity(new Intent(send).setComponent(target));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.bug_report_no_mail_use_file, Toast.LENGTH_LONG).show();
        }
    }

    // ---- to a file ----

    private void saveReport(@NonNull Uri uri) {
        GameInstance instance = selected();
        if (instance == null) return;
        Context app = requireContext().getApplicationContext();
        Toast.makeText(app, R.string.bug_report_preparing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("cannot open the file");
                LogExporter.Result r = LogExporter.export(app, instance, out);
                error = r.ok() ? null : r.error;
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            final String msg = error == null ? app.getString(R.string.bug_report_saved)
                    : app.getString(R.string.bug_report_save_failed, error);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
        }, "bug-report-save").start();
    }
}

package com.ndspack.generator;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_ROM = 1, REQ_SAVE = 2;

    private ExecutorService worker = Executors.newSingleThreadExecutor();

    private Uri romUri;
    private NdsRom rom;
    private Bitmap iconBitmap;  // 32x32 original do banner (ou null)
    private File resultApk;

    private TextView info, status;
    private ImageView preview;
    private EditText nameEdit;
    private Button pickBtn, buildBtn, installBtn, saveBtn;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
    }

    // ------------------------------------------------------------------ interface
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private Button button(String text, boolean primary) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setTextColor(primary ? Color.BLACK : Color.WHITE);
        bt.setBackgroundColor(primary ? 0xFFFFB347 : 0xFF2A2F45);
        bt.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        bt.setLayoutParams(lp);
        return bt;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF12141F);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(36), dp(20), dp(24));
        sv.addView(col);

        col.addView(text("NDS para APK", 26, Color.WHITE, true));
        TextView sub = text("Transforme uma ROM .nds sua em um app que abre direto no jogo.", 14, 0xFFB8BED6, false);
        sub.setPadding(0, dp(6), 0, dp(16));
        col.addView(sub);

        pickBtn = button("Escolher ROM (.nds)", true);
        pickBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_ROM);
        });
        col.addView(pickBtn);

        preview = new ImageView(this);
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(96), dp(96));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(20);
        preview.setLayoutParams(plp);
        col.addView(preview);

        info = text("", 13, 0xFFB8BED6, false);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(8), 0, dp(8));
        col.addView(info);

        nameEdit = new EditText(this);
        nameEdit.setHint("Nome do app");
        nameEdit.setTextColor(Color.WHITE);
        nameEdit.setHintTextColor(0xFF6E7592);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEdit.setSingleLine(true);
        nameEdit.setVisibility(View.GONE);
        col.addView(nameEdit);

        buildBtn = button("Gerar APK", true);
        buildBtn.setVisibility(View.GONE);
        buildBtn.setOnClickListener(v -> generate());
        col.addView(buildBtn);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        col.addView(progress);

        status = text("", 14, 0xFFE6E9F5, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, dp(8));
        col.addView(status);

        installBtn = button("Instalar agora", true);
        installBtn.setVisibility(View.GONE);
        installBtn.setOnClickListener(v -> install());
        col.addView(installBtn);

        saveBtn = button("Salvar o APK em um arquivo", false);
        saveBtn.setVisibility(View.GONE);
        saveBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_TITLE, safeFileName(nameEdit.getText().toString()) + ".apk");
            startActivityForResult(i, REQ_SAVE);
        });
        col.addView(saveBtn);

        TextView foot = text("Use apenas jogos que você mesmo possui. O APK gerado contém a sua ROM e o emulador melonDS (GPLv3); "
                + "não o compartilhe.", 11, 0xFF6E7592, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(28), 0, 0);
        col.addView(foot);

        setContentView(sv);
    }

    // ------------------------------------------------------------------ escolha da ROM
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_ROM) {
            loadRom(data.getData());
        } else if (req == REQ_SAVE) {
            saveApkTo(data.getData());
        }
    }

    private void loadRom(Uri uri) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                FileChannel ch = fis.getChannel()) {
            NdsRom r = NdsRom.parse(ch);
            romUri = uri;
            rom = r;
            resultApk = null;
            iconBitmap = null;
            if (r.icon != null) {
                iconBitmap = Bitmap.createBitmap(r.icon, 32, 32, Bitmap.Config.ARGB_8888);
                preview.setImageBitmap(Bitmap.createScaledBitmap(iconBitmap, 192, 192, false));
                preview.setVisibility(View.VISIBLE);
            } else {
                preview.setVisibility(View.GONE);
            }
            nameEdit.setText(r.suggestedName());
            nameEdit.setVisibility(View.VISIBLE);
            String warn = r.logoOk ? "" : "\nAviso: o cabeçalho não parece o de uma ROM de DS padrão; pode não abrir.";
            info.setText(displayName(uri) + "\nCódigo " + r.gameCode + " · " + (r.size / (1024 * 1024)) + " MB" + warn);
            buildBtn.setVisibility(View.VISIBLE);
            installBtn.setVisibility(View.GONE);
            saveBtn.setVisibility(View.GONE);
            status.setText("");
        } catch (Exception e) {
            toastError("Não consegui ler a ROM: " + e.getMessage());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
            // sem nome: usa o texto padrao
        }
        return "ROM selecionada";
    }

    // ------------------------------------------------------------------ geracao
    private void generate() {
        if (rom == null || romUri == null) return;
        final String name = nameEdit.getText().toString().trim().isEmpty()
                ? rom.suggestedName() : nameEdit.getText().toString().trim();
        final String pkg = rom.packageName();
        final Uri uri = romUri;
        setBusy(true);
        worker.execute(() -> {
            try {
                File tpl = new File(getCacheDir(), "template.apk");
                copyAsset("template.apk", tpl);
                File outDir = new File(getFilesDir(), "out");
                outDir.mkdirs();
                File unsigned = new File(outDir, "unsigned.apk");
                File signed = new File(outDir, "game.apk");

                final long romLen;
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                    romLen = pfd.getStatSize();
                }
                ApkBuilder.build(tpl, unsigned, new ApkBuilder.RomSource() {
                    @Override
                    public InputStream open() throws IOException {
                        InputStream in = getContentResolver().openInputStream(uri);
                        if (in == null) throw new IOException("Não consegui reabrir a ROM");
                        return in;
                    }

                    @Override
                    public long length() {
                        return romLen;
                    }
                }, name, pkg, this::iconPng, msg -> runOnUiThread(() -> status.setText(msg)));

                runOnUiThread(() -> status.setText("Assinando o APK…"));
                try (InputStream ks = getAssets().open("signing.p12")) {
                    ApkSign.sign(ks, unsigned, signed);
                }
                unsigned.delete();
                tpl.delete();
                resultApk = signed;
                runOnUiThread(() -> {
                    setBusy(false);
                    status.setText("Pronto! " + name + " (" + (signed.length() / (1024 * 1024)) + " MB)");
                    installBtn.setVisibility(View.VISIBLE);
                    saveBtn.setVisibility(View.VISIBLE);
                });
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                runOnUiThread(() -> {
                    setBusy(false);
                    toastError("Falha ao gerar: " + msg);
                });
            }
        });
    }

    /** PNG quadrado do icone do jogo (ampliado sem suavizacao), ou null para o icone padrao. */
    private byte[] iconPng(int size) throws IOException {
        if (iconBitmap == null) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(iconBitmap, size, size, false);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        buildBtn.setEnabled(!busy);
        pickBtn.setEnabled(!busy);
        if (busy) {
            installBtn.setVisibility(View.GONE);
            saveBtn.setVisibility(View.GONE);
            status.setText("Gerando…");
        }
    }

    private void copyAsset(String asset, File dst) throws IOException {
        try (InputStream in = getAssets().open(asset); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void toastError(String msg) {
        status.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ saida
    private void saveApkTo(Uri dst) {
        if (resultApk == null) return;
        final File src = resultApk;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(src); OutputStream out = getContentResolver().openOutputStream(dst)) {
                if (out == null) throw new IOException("destino indisponível");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                runOnUiThread(() -> Toast.makeText(this, "APK salvo", Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                runOnUiThread(() -> toastError("Falha ao salvar: " + e.getMessage()));
            }
        });
    }

    private void install() {
        if (resultApk == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Permita instalar apps desta fonte e toque em Instalar de novo.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        final File src = resultApk;
        worker.execute(() -> {
            try {
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setSize(src.length());
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(src);
                            OutputStream out = s.openWrite("game.apk", 0, src.length())) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent cb = new Intent(this, InstallReceiver.class).setAction(InstallReceiver.ACTION);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pend = PendingIntent.getBroadcast(this, id, cb, flags);
                    s.commit(pend.getIntentSender());
                }
            } catch (Exception e) {
                runOnUiThread(() -> toastError("Falha ao instalar: " + e.getMessage()));
            }
        });
    }

    private static String safeFileName(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._ -]", "").trim();
        return t.isEmpty() ? "jogo-nds" : t;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}

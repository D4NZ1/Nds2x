package com.ndspack.generator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Recebe o resultado da instalacao e abre a tela de confirmacao do sistema quando necessario. */
public final class InstallReceiver extends BroadcastReceiver {
    static final String ACTION = "com.ndspack.generator.INSTALL_RESULT";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText(ctx, "Jogo instalado! Procure o ícone na tela inicial.", Toast.LENGTH_LONG).show();
                break;
            default: {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(ctx, "Instalação não concluída" + (msg != null ? ": " + msg : ""),
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}

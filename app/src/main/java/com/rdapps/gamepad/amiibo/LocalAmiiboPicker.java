package com.rdapps.gamepad.amiibo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.rdapps.gamepad.R;
import com.rdapps.gamepad.protocol.JoyController;
import com.rdapps.gamepad.util.PreferenceUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Seletor rápido de Amiibos (.bin) a partir do diretório local.
 *
 * <p>Escaneia recursivamente {@link #AMIIBO_ROOT_PATH}, exibe uma lista filtrável
 * dentro de um AlertDialog e, ao selecionar um arquivo, atualiza as preferências
 * e os bytes ativos no JoyController sem fechar a tela de emulação.
 *
 * <h3>Permissões</h3>
 * <ul>
 *   <li>API 28–29: {@code READ_EXTERNAL_STORAGE} solicitado em runtime.</li>
 *   <li>API 30+: {@code MANAGE_EXTERNAL_STORAGE} — o usuário é redirecionado à
 *       tela "Acesso a todos os arquivos" do sistema se a permissão não estiver
 *       concedida.</li>
 * </ul>
 */
public class LocalAmiiboPicker {

    /** Raiz onde os arquivos .bin são procurados recursivamente. */
    public static final String AMIIBO_ROOT_PATH =
            "/storage/emulated/0/Games/Amiibos";

    private static final String BIN_EXTENSION = ".bin";

    private static boolean sDialogShowing = false;

    // -------------------------------------------------------------------------
    // Ponto de entrada público
    // -------------------------------------------------------------------------

    /**
     * Verifica permissões e, se OK, abre o diálogo de seleção.
     *
     * @param fragment   Fragment pai (deve estar attached).
     * @param controller JoyController ativo para atualização imediata dos bytes.
     */
    public static void show(Fragment fragment, JoyController controller) {
        if (sDialogShowing || fragment == null || !fragment.isAdded()) {
            return;
        }
        Context ctx = fragment.getContext();
        if (ctx == null) {
            return;
        }

        if (needsStoragePermission(ctx)) {
            requestPermissionAndShow(fragment);
        } else {
            openDialog(fragment, controller);
        }
    }

    // -------------------------------------------------------------------------
    // Permissão em runtime
    // -------------------------------------------------------------------------

    /**
     * Retorna {@code true} se ainda precisamos de permissão de armazenamento.
     * <ul>
     *   <li>API 30+: verifica {@link Environment#isExternalStorageManager()}.</li>
     *   <li>API 28–29: verifica {@code READ_EXTERNAL_STORAGE}.</li>
     * </ul>
     */
    private static boolean needsStoragePermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return !Environment.isExternalStorageManager();
        }
        // API 28–29
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Solicita a permissão adequada conforme a versão do Android.
     * <ul>
     *   <li>API 30+: abre a tela de sistema "Acesso a todos os arquivos".</li>
     *   <li>API 28–29: chama {@link Activity#requestPermissions}.</li>
     * </ul>
     */
    private static void requestPermissionAndShow(Fragment fragment) {
        Activity activity = fragment.getActivity();
        if (activity == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: redireciona às configurações do sistema sem Toast atrasado.
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                // Fallback: tela genérica de gerenciamento de arquivos.
                Intent fallback = new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                activity.startActivity(fallback);
            }
        } else {
            // Android 9–10: solicitação de runtime clássica.
            activity.requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    0x4A43);
            Toast.makeText(activity,
                    "Permissão solicitada. Toque novamente após conceder.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------------------------------------------------------------
    // Diálogo principal
    // -------------------------------------------------------------------------

    private static void openDialog(Fragment fragment, JoyController controller) {
        Context ctx = fragment.getContext();
        if (ctx == null) {
            return;
        }

        List<File> allFiles = scanBinFiles(new File(AMIIBO_ROOT_PATH));

        if (allFiles.isEmpty()) {
            Toast.makeText(ctx,
                    "Nenhum arquivo .bin encontrado em:\n" + AMIIBO_ROOT_PATH,
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Monta a View do diálogo sem XML extra
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int dp8 = dp(ctx, 8);
        int dp12 = dp(ctx, 12);
        root.setPadding(dp12, dp8, dp12, dp8);

        // Destaque do Amiibo ativo no topo do diálogo
        String activeName = PreferenceUtils.getAmiiboFileName(ctx);
        String activeLabel = (activeName != null && !activeName.trim().isEmpty())
                ? activeName
                : "Nenhum";

        TextView activeView = new TextView(ctx);
        activeView.setText(String.format(Locale.ROOT, "Ativo: %s", activeLabel));
        activeView.setTypeface(null, Typeface.BOLD);
        activeView.setTextColor(ContextCompat.getColor(ctx, R.color.custom_brand_blue));
        activeView.setTextSize(14f);
        LinearLayout.LayoutParams activeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        activeLp.bottomMargin = dp8;
        root.addView(activeView, activeLp);

        // Campo de busca
        EditText search = new EditText(ctx);
        search.setHint("Buscar Amiibo…");
        search.setSingleLine(true);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp8;
        root.addView(search, searchLp);

        // RecyclerView com a lista (altura fixa para caber no AlertDialog)
        RecyclerView recycler = new RecyclerView(ctx);
        recycler.setLayoutManager(new LinearLayoutManager(ctx));
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 320));
        root.addView(recycler, rvLp);

        AmiiboFileAdapter adapter = new AmiiboFileAdapter(allFiles, AMIIBO_ROOT_PATH);
        recycler.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Selecionar Amiibo")
                .setView(root)
                .setNegativeButton("Cancelar", null)
                .create();

        // Clique em item → carregar Amiibo e fechar diálogo
        adapter.setOnItemClickListener(file -> {
            loadAmiibo(ctx, file, controller);
            dialog.dismiss();
        });

        // Filtro de busca em tempo real
        search.addTextChangedListener(new TextWatcher() {

            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                // não utilizado
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // não utilizado
            }
        });

        dialog.setOnDismissListener(d -> sDialogShowing = false);
        sDialogShowing = true;
        dialog.show();
    }

    // -------------------------------------------------------------------------
    // Carregamento do arquivo selecionado
    // -------------------------------------------------------------------------

    private static void loadAmiibo(Context ctx, File file, JoyController controller) {
        long fileLength = file.length();
        if (fileLength <= 0 || fileLength > Integer.MAX_VALUE) {
            Toast.makeText(ctx,
                    "Arquivo inválido: " + file.getName(),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] bytes = new byte[(int) fileLength];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = fis.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != bytes.length) {
                Toast.makeText(ctx,
                        "Erro: leitura incompleta de " + file.getName(),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            // 1. Persiste nos SharedPreferences (disponível para próximos reinícios)
            PreferenceUtils.setAmiiboBytes(ctx, bytes);
            PreferenceUtils.setAmiiboFileName(ctx, file.getName());

            // 2. Atualiza os bytes ativos no JoyController (sem reiniciar a sessão BT).
            //    O próximo ciclo de polling NFC em InputReport já usará os novos bytes.
            if (controller != null) {
                controller.setAmiiboBytes(bytes);
            }

            Toast.makeText(ctx,
                    "Amiibo ativado: " + file.getName(),
                    Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            Toast.makeText(ctx,
                    "Erro ao ler " + file.getName() + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------------------------
    // Escaneamento recursivo de arquivos .bin
    // -------------------------------------------------------------------------

    /**
     * Retorna todos os arquivos {@code .bin} dentro de {@code dir} e subdiretórios,
     * ordenados pelo caminho absoluto (preservando a hierarquia pasta/subpasta/nome).
     */
    static List<File> scanBinFiles(File dir) {
        List<File> result = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            return result;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return result;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                result.addAll(scanBinFiles(entry));
            } else if (entry.isFile()
                    && entry.getName().toLowerCase(Locale.ROOT).endsWith(BIN_EXTENSION)) {
                result.add(entry);
            }
        }
        result.sort((a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));
        return result;
    }

    // -------------------------------------------------------------------------
    // RecyclerView Adapter
    // -------------------------------------------------------------------------

    private static class AmiiboFileAdapter
            extends RecyclerView.Adapter<AmiiboFileAdapter.ViewHolder> {

        interface OnItemClickListener {

            void onClick(File file);
        }

        private final List<File> original;

        private List<File> filtered;

        private final String rootPrefix;

        private OnItemClickListener listener;

        AmiiboFileAdapter(List<File> files, String rootPath) {
            this.original = files;
            this.filtered = new ArrayList<>(files);
            this.rootPrefix = rootPath.endsWith("/") ? rootPath : rootPath + "/";
        }

        void setOnItemClickListener(OnItemClickListener l) {
            this.listener = l;
        }

        /**
         * Filtra a lista pelo texto digitado (case-insensitive, busca no caminho relativo).
         */
        void filter(String query) {
            List<File> next = new ArrayList<>();
            String lower = query.toLowerCase(Locale.ROOT).trim();
            for (File f : original) {
                if (lower.isEmpty() || relativePath(f).toLowerCase(Locale.ROOT).contains(lower)) {
                    next.add(f);
                }
            }
            List<File> previous = this.filtered;
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {

                @Override
                public int getOldListSize() {
                    return previous.size();
                }

                @Override
                public int getNewListSize() {
                    return next.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPos, int newPos) {
                    return previous.get(oldPos).equals(next.get(newPos));
                }

                @Override
                public boolean areContentsTheSame(int oldPos, int newPos) {
                    return previous.get(oldPos).equals(next.get(newPos));
                }
            });
            this.filtered = next;
            diff.dispatchUpdatesTo(this);
        }

        /** Retorna o caminho relativo à raiz (ex: "Zelda/link.bin"). */
        private String relativePath(File f) {
            String abs = f.getAbsolutePath();
            return abs.startsWith(rootPrefix) ? abs.substring(rootPrefix.length()) : abs;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            int dp4 = dp(parent.getContext(), 4);
            int dp12 = dp(parent.getContext(), 12);
            tv.setPadding(dp12, dp4 * 3, dp12, dp4 * 3);
            tv.setTextSize(14f);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new ViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File file = filtered.get(position);
            holder.textView.setText(relativePath(file));
            holder.textView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onClick(file);
                }
            });
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {

            final TextView textView;

            ViewHolder(TextView view) {
                super(view);
                textView = view;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilitários
    // -------------------------------------------------------------------------

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}

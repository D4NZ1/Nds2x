import re
def sub(path, old, new):
    s = open(path, encoding='utf-8').read()
    assert s.count(old) == 1, (path, old[:40], s.count(old))
    open(path, 'w', encoding='utf-8').write(s.replace(old, new))

C = 'player/src/main/cpp/player.cpp'
J = 'player/src/main/java/com/ndspack/player/'

sub(C, 'std::map<std::string, std::string> g_options;\n',
'''std::map<std::string, std::string> g_options;
std::mutex g_optMutex;
std::set<std::string> g_optPool;
std::vector<std::pair<std::string, std::string>> g_optDefs;
std::atomic<bool> g_optDirty{false};
std::string g_optionsPath;
''')

sub(C, '''        case ENV_SET_VARIABLES:
            return true;  // as opcoes ficam nos padroes do nucleo
''', '''        case ENV_SET_VARIABLES: {
            std::lock_guard<std::mutex> lk(g_optMutex);
            g_optDefs.clear();
            for (const retro_variable* sv = static_cast<const retro_variable*>(data); sv && sv->key; ++sv)
                g_optDefs.emplace_back(sv->key, sv->value ? sv->value : "");
            return true;
        }
''')

sub(C, '''            retro_variable* v = static_cast<retro_variable*>(data);
            auto it = g_options.find(''', '''            retro_variable* v = static_cast<retro_variable*>(data);
            std::lock_guard<std::mutex> lk(g_optMutex);
            auto it = g_options.find(''')
sub(C, 'v->value = it->second.c_str();', 'v->value = g_optPool.insert(it->second).first->c_str();')
sub(C, '''        case ENV_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;''', '''        case ENV_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = g_optDirty.exchange(false);''')
sub(C, '    loadOptionsFile(str(jOptions));\n', '    loadOptionsFile(str(jOptions));\n    g_optionsPath = str(jOptions);\n')

sub(C, '''JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setFastForward(JNIEnv*, jclass, jboolean ff) {
    g_fastForward = ff == JNI_TRUE;
}
''', '''JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setFastForward(JNIEnv*, jclass, jboolean ff) {
    g_fastForward = ff == JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL Java_com_ndspack_player_Native_getOptions(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(g_optMutex);
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)g_optDefs.size(), sc, nullptr);
    for (size_t i = 0; i < g_optDefs.size(); i++) {
        const std::string& key = g_optDefs[i].first;
        const std::string& def = g_optDefs[i].second;
        size_t semi = def.find("; ");
        std::string desc = semi == std::string::npos ? key : def.substr(0, semi);
        std::string opts = semi == std::string::npos ? std::string() : def.substr(semi + 2);
        auto it = g_options.find(key);
        std::string cur = it != g_options.end() ? it->second : opts.substr(0, opts.find('|'));
        std::string row = key + "\\t" + desc + "\\t" + cur + "\\t" + opts;
        env->SetObjectArrayElement(arr, (jsize)i, env->NewStringUTF(row.c_str()));
    }
    return arr;
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setOption(JNIEnv* env, jclass, jstring jKey, jstring jVal) {
    const char* k = env->GetStringUTFChars(jKey, nullptr);
    const char* v = env->GetStringUTFChars(jVal, nullptr);
    {
        std::lock_guard<std::mutex> lk(g_optMutex);
        g_options[k] = v;
        std::ofstream f(g_optionsPath, std::ios::trunc);
        f << "# Opcoes do nucleo (editadas pelo menu Configuracoes)\\n";
        for (const auto& kv : g_options) f << kv.first << " = " << kv.second << "\\n";
        g_optDirty = true;
    }
    env->ReleaseStringUTFChars(jKey, k);
    env->ReleaseStringUTFChars(jVal, v);
}
''')

sub(J + 'Native.java', '    static native void reset();\n',
'''    static native void reset();

    /** Linhas "chave\\tdescricao\\tatual\\topcao1|opcao2". */
    static native String[] getOptions();

    static native void setOption(String key, String value);
''')

M = J + 'MainActivity.java'
sub(M, '            "Sair do jogo"\n        };', '            "Configurações do núcleo",\n            "Sair do jogo"\n        };')
sub(M, '''                        case 5:
                            finish();
                            return;''', '''                        case 5: openSettings(); return;
                        case 6:
                            finish();
                            return;''')
sub(M, '    private void closeMenu() {', '''    private void openSettings() {
        final String[] rows = Native.getOptions();
        if (rows.length == 0) {
            Toast.makeText(this, "O núcleo não listou opções", Toast.LENGTH_SHORT).show();
            closeMenu();
            return;
        }
        String[] labels = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            String[] p = rows[i].split("\\t", -1);
            labels[i] = p[1] + ": " + p[2];
        }
        new AlertDialog.Builder(this)
                .setTitle("Configurações do núcleo")
                .setItems(labels, (dialog, which) -> pickOption(rows[which].split("\\t", -1)))
                .setOnCancelListener(dialog -> closeMenu())
                .show();
    }

    private void pickOption(final String[] p) {
        final String[] vals = p[3].split("\\\\|");
        int cur = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(p[2])) cur = i;
        new AlertDialog.Builder(this)
                .setTitle(p[1])
                .setSingleChoiceItems(vals, cur, (dialog, which) -> {
                    Native.setOption(p[0], vals[which]);
                    dialog.dismiss();
                    openSettings();
                })
                .setOnCancelListener(dialog -> openSettings())
                .show();
    }

    private void closeMenu() {''')
print("ok")

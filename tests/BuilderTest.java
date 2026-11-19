import com.ndspack.generator.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Teste de ponta a ponta (sem Android): ROM -> NdsRom, modelo -> ApkBuilder. */
public class BuilderTest {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File romFile = new File(dir, "rom.nds");
        try (FileChannel ch = FileChannel.open(romFile.toPath(), StandardOpenOption.READ)) {
            NdsRom r = NdsRom.parse(ch);
            System.out.println("title=" + r.headerTitle + " code=" + r.gameCode + " banner=" + r.bannerTitle
                    + " logoOk=" + r.logoOk + " pkg=" + r.packageName() + " name=" + r.suggestedName());
            System.out.printf("icon[0]=%08x icon[1]=%08x icon[2]=%08x icon[(8+3)*32+8+6]=%08x icon[(8+3)*32+8+7]=%08x%n",
                    r.icon[0], r.icon[1], r.icon[2], r.icon[(8 + 3) * 32 + 8 + 6], r.icon[(8 + 3) * 32 + 8 + 7]);
        }
        for (String enc : new String[] {"utf8", "utf16"}) {
            File out = new File(dir, "out_" + enc + ".apk");
            ApkBuilder.build(new File(dir, "template_" + enc + ".apk"), out, new ApkBuilder.RomSource() {
                public InputStream open() throws IOException { return new FileInputStream(romFile); }
                public long length() { return romFile.length(); }
            }, "Meu Jogo Com Nome Bem Longo Para Testar O Pool", "com.ndspack.game.gatse",
                    size -> new byte[] {(byte) size, 1, 2, 3}, msg -> {});
            System.out.println("built " + out + " " + out.length());
        }
    }
}

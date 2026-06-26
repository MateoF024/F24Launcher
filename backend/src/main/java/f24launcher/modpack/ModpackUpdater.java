package f24launcher.modpack;

import f24launcher.modpack.ModpackInstaller.FileEntry;
import f24launcher.modpack.ModpackInstaller.OverrideEntry;
import f24launcher.modpack.ModpackInstaller.PackContents;
import f24launcher.modpack.ModpackInstaller.Plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lógica pura de la actualización diferencial de modpacks (Fase 4):
 *
 * <ul>
 *   <li><b>Diff</b> entre el manifiesto instalado y el contenido de la nueva versión del
 *       pack (por ruta + hash): añade lo nuevo, reemplaza lo cambiado, quita lo retirado.</li>
 *   <li><b>Reglas de configuración</b>: solo se aplican las configs que el modpack cambió
 *       (las que no cambiaron se respetan, conservando ediciones del usuario). Si el modpack
 *       sí cambió una config, gana el modpack (la sobrescribe).</li>
 *   <li><b>Exclusiones</b>: nunca se tocan estados del usuario (options*.txt, saves, logs,
 *       crash-reports, screenshots…) ni archivos ajenos al modpack (los que nunca estuvieron
 *       en el manifiesto quedan intactos por construcción).</li>
 * </ul>
 */
public final class ModpackUpdater {

    private ModpackUpdater() {}

    /** Archivos de estado del usuario (en la raíz del .minecraft) que nunca se actualizan. */
    private static final Set<String> EXCLUDED_FILES = Set.of(
            "options.txt", "optionsof.txt", "optionsshaders.txt",
            "servers.dat", "servers.dat_old");

    /** Carpetas de estado del usuario que nunca se tocan al actualizar. */
    private static final List<String> EXCLUDED_DIRS = List.of(
            "saves/", "logs/", "crash-reports/", "screenshots/", "backups/");

    /** ¿La ruta (rel al .minecraft) es estado del usuario que la actualización no debe tocar? */
    public static boolean isExcluded(String rel) {
        if (rel == null) return true;
        String low = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String d : EXCLUDED_DIRS) if (low.startsWith(d)) return true;
        return EXCLUDED_FILES.contains(low);
    }

    /**
     * Calcula el plan de actualización entre el manifiesto instalado ({@code old}) y el
     * contenido de la nueva versión ({@code next}).
     */
    public static Plan diff(ModpackManifest old, PackContents next) {
        Map<String, ModpackManifest.Entry> oldByPath = old.byPath();
        Set<String> nextPaths = new HashSet<>();

        List<FileEntry> downloads = new ArrayList<>();
        for (FileEntry fe : next.files()) {
            if (fe.path() == null || fe.path().isBlank()) continue;
            nextPaths.add(fe.path());
            if (isExcluded(fe.path())) continue;
            ModpackManifest.Entry oe = oldByPath.get(fe.path());
            // Sin hash conocido del nuevo archivo → re-descarga conservadora.
            if (oe == null || fe.sha1() == null || fe.sha1().isBlank() || !sameHash(oe.sha1, fe.sha1()))
                downloads.add(fe);
        }

        List<OverrideEntry> extracts = new ArrayList<>();
        for (OverrideEntry ov : next.overrides()) {
            nextPaths.add(ov.path());
            if (isExcluded(ov.path())) continue;
            ModpackManifest.Entry oe = oldByPath.get(ov.path());
            if (oe == null || !sameHash(oe.sha1, ov.sha1()))
                extracts.add(ov);
        }

        List<String> removals = new ArrayList<>();
        for (ModpackManifest.Entry e : old.files) {
            if (e.path == null) continue;
            if (!nextPaths.contains(e.path) && !isExcluded(e.path))
                removals.add(e.path);
        }
        return new Plan(downloads, extracts, removals);
    }

    private static boolean sameHash(String a, String b) {
        return a != null && b != null && !a.isBlank() && !b.isBlank() && a.equalsIgnoreCase(b);
    }

    /**
     * ¿{@code latest} es una versión más nueva que {@code current}? Compara por componentes
     * numéricos (1.2.0 < 1.10.0); con desempate por orden de cadena si son numéricamente
     * iguales. {@code current} vacío (instancia sin versión registrada) → siempre hay novedad
     * si {@code latest} no está vacío.
     */
    public static boolean isNewer(String latest, String current) {
        if (latest == null || latest.isBlank()) return false;
        if (current == null || current.isBlank()) return true;
        if (latest.equals(current)) return false;
        int[] a = parse(latest), b = parse(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return latest.compareTo(current) > 0;
    }

    private static int[] parse(String v) {
        String[] parts = v.trim().split("[^0-9]+");
        List<Integer> nums = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            try { nums.add(Integer.parseInt(p)); } catch (NumberFormatException ignored) {}
        }
        int[] out = new int[nums.size()];
        for (int i = 0; i < out.length; i++) out[i] = nums.get(i);
        return out;
    }
}

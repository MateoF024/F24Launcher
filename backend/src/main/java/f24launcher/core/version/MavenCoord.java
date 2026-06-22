package f24launcher.core.version;

/** Convierte coordenadas Maven (group:artifact:version[:classifier][@ext]) a ruta de repo. */
public final class MavenCoord {

    private MavenCoord() {}

    public static String path(String name) {
        String ext = "jar";
        String n = name;
        int at = n.indexOf('@');
        if (at >= 0) { ext = n.substring(at + 1); n = n.substring(0, at); }
        String[] p = n.split(":");
        String group = p[0].replace('.', '/');
        String artifact = p[1];
        String version = p[2];
        String classifier = p.length >= 4 ? p[3] : null;
        String file = artifact + "-" + version + (classifier != null ? "-" + classifier : "") + "." + ext;
        return group + "/" + artifact + "/" + version + "/" + file;
    }
}

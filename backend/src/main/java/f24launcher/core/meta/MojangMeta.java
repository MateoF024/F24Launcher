package f24launcher.core.meta;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Map;

/**
 * Modelos Gson de los manifiestos de Mojang (version manifest, version JSON,
 * asset index). Solo se mapean los campos que necesita el launcher.
 */
public final class MojangMeta {

    private MojangMeta() {}

    public static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String RESOURCES_URL = "https://resources.download.minecraft.net/";

    // ── version_manifest_v2.json ──
    public static class Manifest {
        public Latest latest;
        public List<Version> versions;
    }
    public static class Latest {
        public String release;
        public String snapshot;
    }
    public static class Version {
        public String id;
        public String type;        // release | snapshot | old_beta | old_alpha
        public String url;
        public String sha1;
        public String releaseTime;
    }

    // ── <version>.json ──
    public static class VersionDetails {
        public String id;
        public String type;
        public String mainClass;
        public String assets;                  // id del asset index
        public String inheritsFrom;            // versión base (perfiles de loader)
        public String minecraftArguments;      // formato antiguo (<1.13)
        public Arguments arguments;             // formato nuevo (>=1.13)
        public AssetIndexRef assetIndex;
        public Downloads downloads;
        public List<Library> libraries;
        public JavaVersion javaVersion;
    }
    public static class Arguments {
        public List<JsonElement> game;
        public List<JsonElement> jvm;
    }
    public static class AssetIndexRef {
        public String id;
        public String url;
        public String sha1;
        public long totalSize;
        public long size;
    }
    public static class Downloads {
        public Artifact client;
        public Artifact server;
    }
    public static class Artifact {
        public String path;
        public String url;
        public String sha1;
        public long size;
    }
    public static class Library {
        public String name;
        public String url;                       // base maven (perfiles de loader: Fabric/Quilt)
        public LibraryDownloads downloads;
        public List<Rule> rules;
        public Map<String, String> natives;     // formato clásico: os → clasificador
    }
    public static class LibraryDownloads {
        public Artifact artifact;
        public Map<String, Artifact> classifiers;
    }
    public static class Rule {
        public String action;                    // allow | disallow
        public OsRule os;
    }
    public static class OsRule {
        public String name;                      // windows | linux | osx
        public String arch;
    }
    public static class JavaVersion {
        public String component;
        public int majorVersion;
    }

    // ── asset index (<assets>.json) ──
    public static class AssetIndexFile {
        public Map<String, AssetObject> objects;
    }
    public static class AssetObject {
        public String hash;
        public long size;
    }
}

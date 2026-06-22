package f24launcher.content;

import java.util.List;
import java.util.Map;

/** DTOs de contenido compartidos entre el servicio, el instalador y la IPC. */
public final class ContentModels {

    private ContentModels() {}

    /** Resultado de búsqueda / tarjeta de proyecto. */
    public record Project(
            String id, String slug, String source, String type,
            String name, String description, String author, String iconUrl,
            long downloads, long follows, List<String> categories, String dateModified,
            String clientSide, String serverSide) {}

    /** Página completa de un proyecto. */
    public record ProjectDetail(
            String id, String slug, String source, String type, String name,
            String description, String body, String bodyFormat, String iconUrl,
            String author, long downloads, long follows, List<String> categories,
            List<String> gallery, Map<String, String> links, String license,
            String clientSide, String serverSide) {}

    /** Una versión/archivo descargable de un proyecto. */
    public record Version(
            String id, String name, String versionNumber, String channel,
            String fileName, String downloadUrl, long size,
            List<String> gameVersions, List<String> loaders,
            String datePublished, List<Dependency> dependencies) {}

    public record Dependency(String projectId, String type) {}

    /** Resultado paginado de búsqueda. */
    public record SearchResult(List<Project> hits, int total) {}

    /**
     * Categoría/faceta de filtrado. {@code parentId} vacío = categoría de primer
     * nivel; en CurseForge algunas categorías son hijas de otras (p. ej. "Addons").
     */
    public record Category(String id, String name, String parentId) {}

    /** Elemento instalado en una instancia. */
    public record InstalledItem(
            String fileName, String type, boolean enabled,
            String source, String projectId, String slug, String name,
            String iconUrl, String author, String versionId, String versionName,
            List<String> categories, String clientSide, String serverSide) {}

    /** Una actualización disponible para un elemento instalado. */
    public record UpdateInfo(String fileName, String type, String versionId, String versionName) {}
}

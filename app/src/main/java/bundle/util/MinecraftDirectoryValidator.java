package bundle.util;

import java.nio.file.Files;
import java.nio.file.Path;

public class MinecraftDirectoryValidator {

    public static class ValidationResult {
        public final boolean isValid;
        public final String errorMessage;
        public final ValidationIssue issue;

        public ValidationResult(boolean isValid, String errorMessage, ValidationIssue issue) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
            this.issue = issue;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, "", ValidationIssue.NONE);
        }

        public static ValidationResult failure(String error, ValidationIssue issue) {
            return new ValidationResult(false, error, issue);
        }
    }

    public enum ValidationIssue {
        NONE,
        DIRECTORY_NOT_EXISTS,
        NOT_MINECRAFT_DIR,
        NO_LAUNCHER_PROFILES,
        NO_VERSIONS_FOLDER,
        INSUFFICIENT_PERMISSIONS
    }

    public static ValidationResult validateMinecraftDirectory(Path directory) {
        if (directory == null) {
            return ValidationResult.failure(
                    "No se ha seleccionado ningún directorio",
                    ValidationIssue.DIRECTORY_NOT_EXISTS
            );
        }

        if (!Files.exists(directory)) {
            return ValidationResult.failure(
                    "El directorio seleccionado no existe",
                    ValidationIssue.DIRECTORY_NOT_EXISTS
            );
        }

        if (!Files.isDirectory(directory)) {
            return ValidationResult.failure(
                    "La ruta seleccionada no es un directorio",
                    ValidationIssue.DIRECTORY_NOT_EXISTS
            );
        }

        if (!Files.isWritable(directory)) {
            return ValidationResult.failure(
                    "No tienes permisos de escritura en este directorio",
                    ValidationIssue.INSUFFICIENT_PERMISSIONS
            );
        }

        Path launcherProfiles = directory.resolve("launcher_profiles.json");
        if (!Files.exists(launcherProfiles)) {
            return ValidationResult.failure(
                    "Este directorio no contiene 'launcher_profiles.json'. Los loaders requieren un directorio .minecraft válido. Ejecuta el launcher de Minecraft al menos una vez en este directorio.",
                    ValidationIssue.NO_LAUNCHER_PROFILES
            );
        }

        Path versionsFolder = directory.resolve("versions");
        if (!Files.exists(versionsFolder)) {
            return ValidationResult.failure(
                    "Falta la carpeta 'versions' en el directorio. Este no parece ser un directorio .minecraft válido.",
                    ValidationIssue.NO_VERSIONS_FOLDER
            );
        }

        if (!looksLikeMinecraftDirectory(directory)) {
            return ValidationResult.failure(
                    "Este directorio no tiene la estructura típica de .minecraft. Asegúrate de seleccionar la carpeta .minecraft correcta.",
                    ValidationIssue.NOT_MINECRAFT_DIR
            );
        }

        return ValidationResult.success();
    }

    private static boolean looksLikeMinecraftDirectory(Path directory) {
        String[] expectedFolders = {"versions", "libraries", "assets"};
        int foundFolders = 0;

        for (String folder : expectedFolders) {
            if (Files.exists(directory.resolve(folder))) {
                foundFolders++;
            }
        }

        return foundFolders >= 2;
    }

    public static ValidationResult validateModpackDirectory(Path directory) {
        if (directory == null) {
            return ValidationResult.failure(
                    "No se ha seleccionado ningún directorio",
                    ValidationIssue.DIRECTORY_NOT_EXISTS
            );
        }

        if (!Files.exists(directory)) {
            return ValidationResult.failure(
                    "El directorio seleccionado no existe",
                    ValidationIssue.DIRECTORY_NOT_EXISTS
            );
        }

        if (!Files.isDirectory(directory)) {
            return ValidationResult.failure(
                    "La ruta seleccionada no es un directorio",
                    ValidationIssue.DIRECTORY_NOT_EXISTS
            );
        }

        if (!Files.isWritable(directory)) {
            return ValidationResult.failure(
                    "No tienes permisos de escritura en este directorio",
                    ValidationIssue.INSUFFICIENT_PERMISSIONS
            );
        }

        return ValidationResult.success();
    }

    public static String getHelpMessage(ValidationIssue issue) {
        switch (issue) {
            case NO_LAUNCHER_PROFILES:
                return "Abre el launcher oficial de Minecraft y ejecuta el juego al menos una vez. " +
                        "Esto creará los archivos necesarios.";

            case NO_VERSIONS_FOLDER:
                return "Ejecuta el launcher de Minecraft para crear la estructura de carpetas completa.";

            case NOT_MINECRAFT_DIR:
                return "Busca la carpeta .minecraft en:\n" +
                        "• Windows: %APPDATA%\\.minecraft\n" +
                        "• macOS: ~/Library/Application Support/minecraft\n" +
                        "• Linux: ~/.minecraft";

            case INSUFFICIENT_PERMISSIONS:
                return "Ejecuta la aplicación como administrador o cambia los permisos del directorio.";

            case DIRECTORY_NOT_EXISTS:
                return "Selecciona un directorio existente.";

            default:
                return "";
        }
    }
}
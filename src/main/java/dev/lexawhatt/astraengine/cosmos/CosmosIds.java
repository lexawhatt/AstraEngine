package dev.lexawhatt.astraengine.cosmos;

/** Canonical catalog identities; these checks do not generate descriptors or access a world. */
public final class CosmosIds {
    private CosmosIds() {
    }

    /**
     * Returns whether an ID is a custom lowercase ASCII namespace and single path segment, at most 64 characters
     * including the colon. Dots, underscores and hyphens are allowed; slashes and empty components are not.
     * Null is invalid. A namespace never aliases Sol or a generated sector ID.
     */
    public static boolean isCustom(String id) {
        return id != null && id.length() <= 64 && id.matches("[a-z0-9_.-]+:[a-z0-9_.-]+");
    }

    /** Returns whether an ID is Sol or a canonical generated sector with signed 32-bit coordinates. */
    public static boolean isBuiltin(String id) {
        if ("sol".equals(id)) {
            return true;
        }
        if (id == null || id.length() > 64 || !id.startsWith("s_")) {
            return false;
        }
        String[] coordinates = id.substring(2).split("_", -1);
        if (coordinates.length != 3) {
            return false;
        }
        try {
            int x = Integer.parseInt(coordinates[0]);
            int y = Integer.parseInt(coordinates[1]);
            int z = Integer.parseInt(coordinates[2]);
            return (x != 0 || y != 0 || z != 0) && id.equals("s_" + x + "_" + y + "_" + z);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /** Returns whether the identity syntax is supported; a custom ID need not exist in any catalog yet. */
    public static boolean isKnownId(String id) {
        return isCustom(id) || isBuiltin(id);
    }

    /** Returns a supported ID unchanged, or throws for null, malformed, or noncanonical identities. */
    public static String requireId(String id) {
        if (!isKnownId(id)) {
            throw new IllegalArgumentException("Unsupported cosmos system ID: " + id);
        }
        return id;
    }
}

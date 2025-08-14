package org.cdpg.dx.rs.indexgenerator;

public class IndexNameCreation {
    public static String tenantPrefixs;
    public static String createIndex(String id) {
        return tenantPrefixs + "__" + id;
    }
}

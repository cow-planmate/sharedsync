package com.sharedsync.shared.repository.helper;

public class CacheKeyHelper {

    private final String cacheKeyPrefix;

    public CacheKeyHelper(String cacheKeyPrefix) {
        this.cacheKeyPrefix = cacheKeyPrefix;
    }

    public String getRedisKey(Object id) {
        return cacheKeyPrefix + ":DATA";
    }

    public String getDeletedSetKey() {
        return cacheKeyPrefix + ":DELETED";
    }

    public String getParentIndexField(Class<?> parentClass, Object parentId) {
        return "P_IDX:" + parentClass.getSimpleName() + ":" + parentId;
    }
    
    public String getCacheKeyPrefix() {
        return cacheKeyPrefix;
    }
}

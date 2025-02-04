/**
 * Copyright 2016-2019 Cloudera, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.hortonworks.registries.schemaregistry.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.hortonworks.registries.schemaregistry.SchemaIdVersion;
import com.hortonworks.registries.schemaregistry.SchemaVersionInfo;
import com.hortonworks.registries.schemaregistry.SchemaVersionKey;
import com.hortonworks.registries.schemaregistry.SchemaVersionRetriever;
import com.hortonworks.registries.schemaregistry.errors.SchemaNotFoundException;
import com.hortonworks.registries.shaded.javax.ws.rs.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Loading cache for {@link Key} with values {@link SchemaVersionInfo}.
 */
public class SchemaVersionInfoCache implements AbstractCache {
    private static final Logger LOG = LoggerFactory.getLogger(SchemaVersionInfoCache.class);

    private final LoadingCache<Key, SchemaVersionInfo> loadingCache;
    private final ConcurrentMap<SchemaIdVersion, SchemaVersionKey> idWithNameVersion;
    private final ConcurrentMap<SchemaVersionKey, List<SchemaIdVersion>> nameVersionWithIds;

    public SchemaVersionInfoCache(final SchemaVersionRetriever schemaRetriever,
                                  final int schemaCacheSize,
                                  final long schemaCacheExpiryInMilliSecs) {
        idWithNameVersion = new ConcurrentHashMap<>(schemaCacheSize);
        nameVersionWithIds = new ConcurrentHashMap<>(schemaCacheSize);
        loadingCache = createLoadingCache(schemaRetriever, schemaCacheSize, schemaCacheExpiryInMilliSecs);
    }

    private LoadingCache<Key, SchemaVersionInfo> createLoadingCache(SchemaVersionRetriever schemaRetriever,
                                                                    int schemaCacheSize,
                                                                    long schemaCacheExpiryInMilliSecs) {
        return CacheBuilder.newBuilder()
                           .maximumSize(schemaCacheSize)
                           .expireAfterAccess(schemaCacheExpiryInMilliSecs, TimeUnit.MILLISECONDS)
                           .build(new CacheLoader<Key, SchemaVersionInfo>() {
                                       @Override
                                       public SchemaVersionInfo load(Key key) throws Exception {
                                           LOG.debug("Key is not in cache: [{}]. Loading from from target service", key);
                                           SchemaVersionInfo schemaVersionInfo;
                                           if (key.schemaVersionKey != null) {
                                               schemaVersionInfo = schemaRetriever.retrieveSchemaVersion(key.schemaVersionKey);
                                           } else if (key.schemaIdVersion != null) {
                                               schemaVersionInfo = schemaRetriever.retrieveSchemaVersion(key.schemaIdVersion);
                                           } else {
                                               throw new IllegalArgumentException("Given argument is not valid: " + key);
                                           }

                                           LOG.debug("Update cache for entry {}", schemaVersionInfo);
                                           updateCacheInvalidationEntries(schemaVersionInfo);
                                           LOG.trace("Return version {}", schemaVersionInfo);
                                           return schemaVersionInfo;
                                       }
                                   });
    }

    private void updateCacheInvalidationEntries(SchemaVersionInfo schemaVersionInfo) {
        // need to support this as SchemaIdVersion supports multiple ways to construct for backward compatible APIs
        // this would have been simple without that.
        SchemaVersionKey schemaVersionKey = new SchemaVersionKey(schemaVersionInfo.getName(), schemaVersionInfo.getVersion());
        SchemaIdVersion key1 = new SchemaIdVersion(schemaVersionInfo.getId());
        idWithNameVersion.putIfAbsent(key1, schemaVersionKey);
        Long schemaMetadataId = schemaVersionInfo.getSchemaMetadataId();

        // schemaMetadataId can be null from earlier registry instances.
        if (schemaMetadataId != null) {
            SchemaIdVersion key2 = new SchemaIdVersion(schemaMetadataId, schemaVersionInfo.getVersion());
            nameVersionWithIds.putIfAbsent(schemaVersionKey, Lists.newArrayList(key1, key2));
            idWithNameVersion.putIfAbsent(key2, schemaVersionKey);
        } else {
            nameVersionWithIds.putIfAbsent(schemaVersionKey, Collections.singletonList(key1));
        }
    }

    public SchemaVersionInfo getSchema(SchemaVersionInfoCache.Key key) throws SchemaNotFoundException {
        try {
            LOG.debug("Trying to load entry for cache with key [{}] from target service", key);
            SchemaVersionInfo schemaVersionInfo = loadingCache.get(key);
            LOG.trace("Result: {}", schemaVersionInfo);
            return schemaVersionInfo;
        } catch (ExecutionException e) {
            if (e.getCause().getClass() == SchemaNotFoundException.class) {
                SchemaNotFoundException exception = (SchemaNotFoundException) e.getCause();
                if (key.schemaVersionKey != null) {
                    throw new SchemaNotFoundException(exception.getMessage(), key.schemaVersionKey.toString());
                } else {
                    throw exception;
                }
            }
            throw new RuntimeException(e);
        } catch (UncheckedExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException) {
                throw new SchemaNotFoundException(key.toString(), cause);
            } else if (cause instanceof RuntimeException) {
                // Do not expose cache implementation details to the caller
                throw (RuntimeException) cause;
            } else {
                // Should not happen, best option is to rethrow
                throw e;
            }
        }
    }

    public SchemaVersionInfo getSchemaIfPresent(SchemaVersionInfoCache.Key key) throws SchemaNotFoundException {
        LOG.debug("Trying to get entry from cache if it is present in local cache with key [{}]", key);
        return loadingCache.getIfPresent(key);
    }

    public void invalidateSchema(SchemaVersionInfoCache.Key key) {
        LOG.debug("Invalidating cache entry for key [{}]", key);
        loadingCache.invalidate(key);

        SchemaVersionKey schemaVersionKey =
                key.schemaIdVersion != null ? idWithNameVersion.get(key.schemaIdVersion) : key.schemaVersionKey;

        // it can be null if it is not accessed earlier.
        if (schemaVersionKey != null) {
            loadingCache.invalidate(Key.of(schemaVersionKey));
            List<SchemaIdVersion> schemaIdVersions = nameVersionWithIds.get(schemaVersionKey);
            if (schemaIdVersions != null) {
                for (SchemaIdVersion schemaIdVersion : schemaIdVersions) {
                    loadingCache.invalidate(Key.of(schemaIdVersion));
                }
            }
        }
    }

    public void invalidateAll() {
        LOG.info("Invalidating all the cache entries");

        loadingCache.invalidateAll();
    }

    @Override
    public SchemaRegistryCacheType getCacheType() {
        return SchemaRegistryCacheType.SCHEMA_VERSION_CACHE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Key {

        @JsonProperty
        private SchemaVersionKey schemaVersionKey;

        @JsonProperty
        private SchemaIdVersion schemaIdVersion;

        public Key(SchemaVersionKey schemaVersionKey) {
            this.schemaVersionKey = schemaVersionKey;
        }

        public Key(SchemaIdVersion schemaIdVersion) {
            this.schemaIdVersion = schemaIdVersion;
        }

        public static Key of(SchemaVersionKey schemaVersionKey) {
            return new Key(schemaVersionKey);
        }

        public static Key of(SchemaIdVersion schemaIdVersion) {
            return new Key(schemaIdVersion);
        }

        // For JSON serialization/deserialization
        private Key() {

        }

        @Override
        public String toString() {
            return "Key {" +
                    "schemaVersionKey=" + schemaVersionKey +
                    ", schemaIdVersion=" + schemaIdVersion +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Key key = (Key) o;

            if (schemaVersionKey != null ? !schemaVersionKey.equals(key.schemaVersionKey)
                                         : key.schemaVersionKey != null) {
                return false;
            }
            return schemaIdVersion != null ? schemaIdVersion.equals(key.schemaIdVersion) : key.schemaIdVersion == null;
        }

        @Override
        public int hashCode() {
            int result = schemaVersionKey != null ? schemaVersionKey.hashCode() : 0;
            result = 31 * result + (schemaIdVersion != null ? schemaIdVersion.hashCode() : 0);
            return result;
        }
    }

}

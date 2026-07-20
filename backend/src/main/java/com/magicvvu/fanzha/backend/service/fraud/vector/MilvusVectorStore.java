package com.magicvvu.fanzha.backend.service.fraud.vector;

import com.magicvvu.fanzha.backend.config.VectorStoreProperties;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.MutationResultWrapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class MilvusVectorStore {

    private static final String FIELD_URL = "url";
    private static final String FIELD_CONTENT_HASH = "content_hash";
    private static final String FIELD_SOURCE = "source";
    private static final String FIELD_PUBLISH_TIME = "publish_time";
    private static final String FIELD_VECTOR = "vector";

    private final VectorStoreProperties.MilvusProperties properties;
    private volatile MilvusClient client;
    private volatile boolean initialized = false;

    public void upsert(String url, String contentHash, String source, String publishTime, List<Float> vector) {
        ensureInitialized();

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(FIELD_URL, java.util.Collections.singletonList(url)));
        fields.add(new InsertParam.Field(FIELD_CONTENT_HASH, java.util.Collections.singletonList(contentHash)));
        fields.add(new InsertParam.Field(FIELD_SOURCE, java.util.Collections.singletonList(source)));
        fields.add(new InsertParam.Field(FIELD_PUBLISH_TIME, java.util.Collections.singletonList(publishTime)));
        fields.add(new InsertParam.Field(FIELD_VECTOR, java.util.Collections.singletonList(vector)));

        InsertParam param = InsertParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withFields(fields)
                .build();

        MutationResult result = client.insert(param).getData();
        new MutationResultWrapper(result);
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) return;
            if (client == null) {
                client = new MilvusServiceClient(
                        ConnectParam.newBuilder()
                                .withHost(properties.getHost())
                                .withPort(properties.getPort())
                                .build()
                );
            }

            boolean exists = client.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(properties.getCollection()).build()
            ).getData();
            if (!exists) {
                List<FieldType> fields = new ArrayList<>();
                fields.add(FieldType.newBuilder()
                        .withName(FIELD_URL)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(512)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build());
                fields.add(FieldType.newBuilder()
                        .withName(FIELD_CONTENT_HASH)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(128)
                        .build());
                fields.add(FieldType.newBuilder()
                        .withName(FIELD_SOURCE)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(128)
                        .build());
                fields.add(FieldType.newBuilder()
                        .withName(FIELD_PUBLISH_TIME)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build());
                fields.add(FieldType.newBuilder()
                        .withName(FIELD_VECTOR)
                        .withDataType(DataType.FloatVector)
                        .withDimension(properties.getDim())
                        .build());

                CreateCollectionParam.Builder createBuilder = CreateCollectionParam.newBuilder()
                        .withCollectionName(properties.getCollection())
                        .withDescription("fraud case vectors")
                        .withShardsNum(2);
                for (FieldType f : fields) {
                    createBuilder.addFieldType(f);
                }
                CreateCollectionParam createParam = createBuilder.build();
                client.createCollection(createParam);

                Map<String, Object> extra = new HashMap<>();
                extra.put("nlist", properties.getNlist());
                client.createIndex(CreateIndexParam.newBuilder()
                        .withCollectionName(properties.getCollection())
                        .withFieldName(FIELD_VECTOR)
                        .withIndexType(IndexType.IVF_FLAT)
                        .withMetricType(MetricType.COSINE)
                        .withExtraParam("{\"nlist\":" + properties.getNlist() + "}")
                        .build());
            }

            client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(properties.getCollection()).build());
            initialized = true;
        }
    }
}

package com.dtstack.flinkx.mongodb;

import com.dtstack.flinkx.exception.WriteRecordException;
import com.google.common.collect.Lists;
import com.mongodb.*;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.apache.flink.types.Row;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dtstack.flinkx.mongodb.MongodbConfigKeys.*;

/**
 * @author jiangbo
 * @date 2018/6/5 10:40
 */
public class MongodbUtil {

    private static final Logger LOG = LoggerFactory.getLogger(MongodbUtil.class);

    private static final String HOST_SPLIT_REGEX = ",\\s*";

    private static final String COLUMN_NAME_SPLIT_REGEX = "\\.";

    private static Pattern HOST_PORT_PATTERN = Pattern.compile("(?<host>(\\d{0,3}\\.){3}\\d{0,3})(:(?<port>\\d+))*");

    private static final Integer DEFAULT_PORT = 27017;

    private static MongoClient mongoClient;

    /**
     * Get mongo client
     * @param config
     * @return MongoClient
     */
    public static MongoClient getMongoClient(Map<String,String> config){
        try{
            if(mongoClient != null){
                MongoClientOptions options = getOption();
                List<ServerAddress> serverAddress = getServerAddress(config.get(KEY_HOST_PORTS));
                String username = config.get(KEY_USERNAME);
                String password = config.get(KEY_PASSWORD);
                String database = config.get(KEY_DATABASE);

                if(username == null){
                    mongoClient = new MongoClient(serverAddress,options);
                } else {
                    MongoCredential credential = MongoCredential.createScramSha1Credential(username, database, password.toCharArray());
                    List<MongoCredential> credentials = Lists.newArrayList();
                    credentials.add(credential);

                    mongoClient = new MongoClient(serverAddress,credentials,options);
                }


                LOG.info("mongo客户端获取成功");
            }
            return mongoClient;
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    public static MongoDatabase getDatabase(Map<String,String> config,String database){
        MongoClient client = getMongoClient(config);
        return mongoClient.getDatabase(database);
    }

    public static MongoCollection<Document> getCollection(Map<String,String> config,String database, String collection){
        MongoClient client = getMongoClient(config);
        MongoDatabase db = client.getDatabase(database);

        boolean exist = false;
        for (String name : db.listCollectionNames()) {
            if(name.equals(collection)){
                exist = true;
            }
        }

        if(!exist){
            throw new RuntimeException("can not find collection '" + collection + "' from database '" + database + "'.");
        }

        return db.getCollection(collection);
    }

    public static void close(){
        if (mongoClient != null){
            mongoClient.close();
            mongoClient = null;
        }
    }

    public static Row convertDocTORow(Document doc,List<String> columnNames){
        Row row = new Row(columnNames.size());

        for (int i = 0; i < columnNames.size(); i++) {
            String name = columnNames.get(i);
            if(name.matches(COLUMN_NAME_SPLIT_REGEX)){
                String[] parts = name.split(COLUMN_NAME_SPLIT_REGEX);
                Document current = doc;
                for (int j = 0; j < parts.length - 1; j++) {
                    if (current.containsKey(parts[j])){
                        current = (Document) doc.get(parts[j]);
                    } else {
                        break;
                    }
                }

                row.setField(i,current.getOrDefault(parts[parts.length - 1],null));
            } else {
                row.setField(i,doc.getOrDefault(name,null));
            }
        }

        return row;
    }

    public static Document convertRowToDoc(Row row,List<String> columnNames,List<String> updateColumns) throws WriteRecordException {
        Document doc = new Document();
        if(updateColumns == null || updateColumns.size() == 0){
            for (int i = 0; i < columnNames.size(); i++) {
                doc.append(columnNames.get(i),row.getField(i));
            }
        } else {
            for (int i = 0; i < updateColumns.size(); i++) {
                doc.append(updateColumns.get(i),row.getField(i));
            }
        }
        return doc;
    }

    /**
     * parse server address from hostPorts string
     */
    private static List<ServerAddress> getServerAddress(String hostPorts){
        List<ServerAddress> addresses = Lists.newArrayList();

        for (String hostPort : hostPorts.split(HOST_SPLIT_REGEX)) {
            if(hostPort.length() == 0){
                continue;
            }

            Matcher matcher = HOST_PORT_PATTERN.matcher(hostPort);
            if(matcher.find()){
                String host = matcher.group("host");
                String portStr = matcher.group("port");
                int port = portStr == null ? DEFAULT_PORT : Integer.parseInt(portStr);

                ServerAddress serverAddress = new ServerAddress(host,port);
                addresses.add(serverAddress);
            }
        }

        return addresses;
    }

    private static MongoClientOptions getOption(){
        MongoClientOptions.Builder build = new MongoClientOptions.Builder();
        build.connectionsPerHost(100);
        build.threadsAllowedToBlockForConnectionMultiplier(100);
        build.connectTimeout(10000);
        build.maxWaitTime(5000);
        build.socketTimeout(0);
        build.writeConcern(WriteConcern.UNACKNOWLEDGED);
        return build.build();
    }
}

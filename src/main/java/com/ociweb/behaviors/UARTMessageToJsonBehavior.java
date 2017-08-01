package com.ociweb.behaviors;


import com.ociweb.gl.api.PubSubListener;
import com.ociweb.iot.maker.FogCommandChannel;
import com.ociweb.iot.maker.FogRuntime;
import com.ociweb.pronghorn.pipe.BlobReader;
import com.ociweb.pronghorn.util.TrieParser;
import com.ociweb.pronghorn.util.TrieParserReader;
import com.ociweb.schema.FieldType;
import com.ociweb.schema.MessageScheme;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.ociweb.schema.MessageScheme.*;

public class UARTMessageToJsonBehavior implements PubSubListener {
    private final FogCommandChannel cmd;
    private final String manifoldSerial;
    private final String publishTopic;
    private final TrieParser parser = MessageScheme.buildParser();
    private final TrieParserReader reader = new TrieParserReader(4, true);

    private final int batchCountLimit = 1;
    private int batchCount = batchCountLimit;

    private Map<Integer, StringBuilder> stations = new HashMap<>();

    public UARTMessageToJsonBehavior(FogRuntime runtime, String manifoldSerial, String publishTopic) {
        this.cmd = runtime.newCommandChannel();
        this.cmd.ensureDynamicMessaging(64, jsonMessageSize);
        this.manifoldSerial = manifoldSerial;
        this.publishTopic = publishTopic;
    }

    @Override
    public boolean message(CharSequence charSequence, BlobReader messageReader) {
        final long timeStamp = messageReader.readLong();
        //StringBuilder a = new StringBuilder();
        //messageReader.readUTF(a);
        //System.out.println(String.format("E) Recieved: %d:'%s'", a.length(), a.toString()));
        final short messageLength = messageReader.readShort();
        //System.out.println("E) Length: " + messageLength);
        reader.parseSetup(messageReader, messageLength);
        int stationId = -1;

        StringBuilder json = new StringBuilder();

        json.append("{");

        while (true) {
            // Why return long only to down cast it to int for capture methods?
            int parsedId = (int) TrieParserReader.parseNext(reader, parser);
            //System.out.println("E) Parsed Field: " + parsedId);
            if (parsedId == -1) {
                if (TrieParserReader.parseSkipOne(reader) == -1) {
                    //System.out.println("E) End of Message");
                    break;
                }
            }
            else {
                String key = MessageScheme.jsonKeys[parsedId];
                final FieldType fieldType = MessageScheme.types[parsedId];
                json.append("\"");
                json.append(key);
                json.append("\":");
                switch (fieldType) {
                    case integer: {
                        int value = (int) TrieParserReader.capturedLongField(reader, 0);
                        json.append(value);
                        json.append(",");
                        if (parsedId == 0) {
                            stationId = value;
                        }
                        break;
                    }
                    case string: {
                        json.append("\"");
                        TrieParserReader.capturedFieldBytesAsUTF8(reader, 0, json);
                        json.append("\"");
                        json.append(",");
                        break;
                    }
                    case floatingPoint: {
                        double value = (double) TrieParserReader.capturedLongField(reader, 0);
                        json.append(value);
                        json.append(",");
                        break;
                    }
                }
            }
        }
        json.append("\"" + timestampJsonKey + "\":");
        json.append(timeStamp);
        json.append("},");

        stations.put(stationId, json);

        if (stations.size() > batchCount) {
            StringBuilder all = new StringBuilder();
            all.append("{\""+ manifoldSerialJsonKey + "\":");
            all.append(manifoldSerial);
            all.append(",\"" + stationsJsonKey + "\":[");

            for (StringBuilder station: stations.values()) {
                all.append(station);
            }

            all.delete(all.length()-1, all.length());
            all.append("]}");

            String body = all.toString();
            stations.clear();
            batchCount = ThreadLocalRandom.current().nextInt(1, batchCountLimit + 1);
            System.out.println(String.format("E) %s", body));
            cmd.publishTopic(publishTopic, writer -> {
                writer.writeUTF(body);
            });
        }
        return true;
    }
}

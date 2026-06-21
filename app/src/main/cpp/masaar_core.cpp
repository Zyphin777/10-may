// masaar_core.cpp
#include "masaar_core.h"
#include <string>

// لو عايزة helpers بسيطة للـ parsing
static std::string node_id = "unknown";

void setNodeId(const std::string& id) {
    node_id = id;
}

std::string buildMessage(const std::string& payload, const std::string& dst) {
    std::string json = R"({"type":"DATA","src":")" + node_id +
                       R"(","dst":")" + dst +
                       R"(","payload":")" + payload + R"("})";
    return json;
}

std::string buildHelloMessage() {
    // HELLO message with src = node_id
    std::string json = R"({"type":"HELLO","src":")" + node_id +
                       R"(","version":"1.0"})";
    return json;
}

// ========== JSON helper بسيط جداً ==========

static std::string getJsonValue(const std::string& json,
                                const std::string& key) {
    // بندوّر على "key":"value"
    std::string pattern = "\"" + key + "\":\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return "";

    pos += pattern.size();
    size_t end = json.find('"', pos);
    if (end == std::string::npos) return "";

    return json.substr(pos, end - pos);
}

// ========== parseMessage implementation ==========

ParsedMessage parseMessage(const std::string& jsonMsg) {
    ParsedMessage msg;

    msg.type    = getJsonValue(jsonMsg, "type");
    msg.src     = getJsonValue(jsonMsg, "src");
    msg.dst     = getJsonValue(jsonMsg, "dst");
    msg.payload = getJsonValue(jsonMsg, "payload");

    if (!msg.type.empty())
        msg.valid = true;

    return msg;
}

// ========== handleIncoming المطوّرة ==========

std::string handleIncoming(const std::string& jsonMsg) {
    ParsedMessage msg = parseMessage(jsonMsg);

    if (!msg.valid) {
        return "Invalid message format";
    }

    // مثال بسيط على التصنيف
    if (msg.type == "HELLO") {
        // هنا بعدين هنضيف: update neighbor table, last_seen, ...
        return "HELLO from " + msg.src;
    } else if (msg.type == "DATA") {
        // هنا هنضيف logic بتاع routing / delivery
        return "DATA from " + msg.src + " to " + msg.dst +
               " payload=" + msg.payload;
    } else {
        return "Unknown type: " + msg.type;
    }
}

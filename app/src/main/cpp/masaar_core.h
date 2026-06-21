#ifndef OFFLINEROUTINGAPP_MASAAR_CORE_H
#define OFFLINEROUTINGAPP_MASAAR_CORE_H

#include <string>

// الأساسيات اللي موجودة عندك
void setNodeId(const std::string& id);
std::string buildMessage(const std::string& payload, const std::string& dst);
std::string handleIncoming(const std::string& jsonMsg);

// Stage 2 — إضافة HELLO message
std::string buildHelloMessage();

// Stage 3 — Reliable Messaging (إضافات بس - مش هنستخدمها دلوقتي)
std::string buildAckMessage(const std::string& dst, int seq);
std::string buildNackMessage(const std::string& dst, int seq, const std::string& reason);
std::string buildDataMessage(const std::string& payload, const std::string& dst, int seq);

// Parsing structure
struct ParsedMessage {
    std::string type;     // HELLO / DATA / ACK / NACK
    std::string src;
    std::string dst;
    std::string payload;
    int seq;
    bool valid;
};

ParsedMessage parseMessage(const std::string& jsonMsg);

#endif //OFFLINEROUTINGAPP_MASAAR_CORE_H

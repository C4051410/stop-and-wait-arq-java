# Reliable UDP Data Transfer Protocol (Stop-and-Wait ARQ)

A custom application-layer transport protocol implemented in Java over raw UDP (`DatagramSocket`), providing guaranteed delivery, flow control, and data integrity over an unreliable network channel using Stop-and-Wait Automatic Repeat reQuest (ARQ) and CRC-32 checksum verification.

---

## Architecture Overview

Standard UDP does not guarantee packet ordering, delivery, or deduplication. This protocol layers a stateful Alternating-Bit Stop-and-Wait ARQ engine on top of UDP datagrams to ensure error-free, sequential file and telemetry transfer.

```text
 Client (Sender)                                       Server (Receiver)
   │                                                         │
   │─── META [SEQ#0] (Total Records, Target File, Patch) ───>│ (Handshake & Init)
   │                                                         │
   │─── DATA [SEQ#1] (Payload Chunk: Batch of Readings) ────>│ (Verify CRC32)
   │<── ACK  [SEQ#1] ────────────────────────────────────────│
   │                                                         │
   │─── DATA [SEQ#0] (Next Payload Chunk) ──────────────────>│
   │    X  (ACK dropped in transit)                          │
   │    ... [Socket Timeout Occurs]                          │
   │─── DATA [SEQ#0] (Retransmit Same Chunk) ───────────────>│ (Detect Duplicate SEQ#0)
   │<── ACK  [SEQ#0] (Re-acknowledge Last Valid) ────────────│ (Discard duplicate data)
   │                                                         │
   │─── [Transfer Completed: Close Sockets] ────────────────>│ (Flush buffer to disk)
# Increment 5 (phần 1) — Giao webhook Bank → Hub qua Kafka

> Thiết kế đã chốt. Chi tiết nền: [outbox-pattern.md](../notes/outbox-pattern.md) ·
> [hmac-request-signing.md](../notes/hmac-request-signing.md) ·
> [compare-and-set.md](../notes/compare-and-set.md)
> Checklist nghiệp vụ: [03-bank-capture-checklist.md](03-bank-capture-checklist.md) mục
> "Webhook Bank → Hub" và "Hub nhận webhook".

---

## 1. Phạm vi

`OutboxRelay.dispatchDueEvents()` hiện là skeleton rỗng. Tài liệu này định nghĩa
phần thịt của nó, và **nó sẽ không gọi HTTP**.

| Trước (dự định ban đầu) | Sau (chốt) |
|---|---|
| relay bốc row → POST webhook → ghi kết quả | relay bốc row → **publish Kafka** → `PUBLISHED` |
| retry = vòng lặp + `next_attempt_at` | retry = **topic**, nhìn thấy được, replay được |
| lỗi vĩnh viễn = row `FAILED` | lỗi vĩnh viễn = message trong **DLT** + row `PARKED` |
| — | **consumer** (trong fake-bank) ký HMAC rồi POST |

## 2. Vì sao Kafka — và vì sao KHÔNG bỏ outbox

### 2.1 Kafka không đảm bảo "webhook chắc chắn được gọi"

Kafka đảm bảo **message không mất và được phát lại**. Nó không làm cú HTTP tới Hub
thành công. Hub sập đủ lâu thì retry ladder cạn và message rơi vào DLT — y như gọi
trực tiếp.

Thứ cho ta "chắc chắn được thực hiện" vẫn là **bảng outbox**: row chưa `SENT` thì
còn bị quét lại, không giới hạn số lần. Kafka chỉ làm giảm tần suất phải dùng tới
lưới đó.

### 2.2 Kafka mua được gì

- **Retry trở thành dữ liệu**, không phải vòng lặp vô hình trong code: message nằm
  trong `-retry-1`, mở ra xem được, đếm được, bơm lại được.
- **Tách lịch retry khỏi job**: relay không phải tự quản `attempt_count`/`next_attempt_at`
  làm lịch leo thang.
- **DLT là nơi đỗ có thật** cho lỗi vĩnh viễn: alert được, replay được.
- **Đệm khi tắc**: Hub chậm thì message dồn trong topic, không dồn trong DB.
- **Học Kafka** — lý do thật, và hợp lệ trong project học.

### 2.3 Vì sao outbox ở lại

Có một suy nghĩ rất dễ nảy ra: "có Kafka rồi, publish luôn trong `debit()`".

Sai, và sai đúng cái lỗi mà outbox sinh ra để chữa. DB commit + Kafka publish vẫn là
**hai hệ thống**:

```
UPDATE balance        commit OK
kafkaTemplate.send()  crash
=> tiền đã trừ, Hub không bao giờ biết
```

Kafka không phải DB của ta. Dual-write là dual-write bất kể hệ thống thứ hai tên gì.

### 2.4 Ghi chú production-vs-here (luật §1 CLAUDE.md)

§3 CLAUDE.md viết: *"Kafka là event backbone. SQS/SNS cho async task + fan-out
webhook/notification + DLQ."*

Theo đúng tài liệu đó thì **giao webhook là việc của SQS** — SQS có `VisibilityTimeout`,
`maxReceiveCount` → DLQ, và **delay queue native**, tức là sinh ra đúng cho bài này.
Kafka phải mô phỏng độ trễ bằng pause partition.

> **Production ở kiến trúc này sẽ dùng SQS cho chiều webhook. Ở đây dùng Kafka vì mục
> tiêu học Kafka là mục tiêu tường minh của project.** §3 giữ nguyên, không sửa.

## 3. Topology

```
       fake-bank                            Kafka                        Hub
       ---------                            -----                        ---
debit() +- UPDATE balance        |
        +- UPDATE txn=SUCCESS    | 1 transaction
        +- INSERT outbox PENDING |
                |
                | afterCommit  +  job 10s (lưới)
                v
      dispatchDueEvents()
        claim FOR UPDATE SKIP LOCKED
        publish -----------------> fakebank.bank-transaction.succeeded
        mark PUBLISHED                 3 partition - RF=3
                                            |
                                            v
                                  consumer (trong fake-bank)
                                    ký HMAC --> POST /bank-callbacks --> Hub
                                    2xx -> UPDATE outbox SENT -> commit offset
                                    lỗi tạm -> |
                                               v
                                    ...-retry-0 (10s) -> -retry-1 (1m)
                                         -> -retry-2 (5m) -> -retry-3 (30m)
                                                     |
                                                     v
                                              ...-dlt (đỗ, alert)
                                              UPDATE outbox PARKED
```

## 4. Vòng đời outbox

```
PENDING  --publish Kafka ack--> PUBLISHED  --webhook 2xx--> SENT
                                     |
                                     +--vào DLT--> PARKED
```

| Status | Nghĩa | Ai đặt |
|---|---|---|
| `PENDING` | đã commit cùng nghiệp vụ, **chưa vào Kafka** | `debit()` |
| `PUBLISHED` | Kafka đã ack (đủ ISR) — **Kafka làm chủ từ đây** | relay |
| `SENT` | Hub đã trả 2xx | consumer |
| `PARKED` | vào DLT, cần can thiệp | DLT handler |

**Migration `V7__outbox_published_parked.sql`** — nới `status` và backfill không cần
(chưa có dữ liệu `SENT`/`FAILED` nào). Enum `OutboxStatus` đổi thành
`PENDING, PUBLISHED, SENT, PARKED`; **bỏ `FAILED`** vì hiện không nơi nào dùng
(chỉ `PENDING` được gán) và nó trùng vai với `PARKED`.

### 4.1 Luật của sweeper — chống nhân bản

Đây là bẫy nặng nhất của thiết kế này. Nếu sweeper 10s vẫn bốc row đã publish:

```
t=0s   publish -> message vào retry ladder 30 phút
t=10s  sweeper thấy row vẫn PENDING -> publish lại
t=20s  publish lại
...
=> ~180 message cho MỘT sự kiện
```

Luật chốt:

- Sweeper 10s **chỉ bốc `PENDING`**. Row đã `PUBLISHED` thì Kafka làm chủ, không chạm.
- Row `PUBLISHED` quá **1 giờ** mà chưa `SENT` → mới republish, và chạy ở **nhịp 15
  phút** riêng. Đây là lưới cho ca "Kafka mất message", cực hiếm.
- Row `PARKED` → **không tự republish**. Chỉ bơm lại khi có người quyết định.

## 5. Topic và message

**Tên topic:** `fakebank.bank-transaction.succeeded`

Theo §5 CLAUDE.md (`<domain>.<event>`, aggregate PastTense). Prefix `fakebank` thay vì
`paymenthub` vì bank là tổ chức khác.

Retry/DLT do `@RetryableTopic` sinh: `...-retry-0`, `-retry-1`, `-retry-2`, `-retry-3`,
`...-dlt`.

**Key = `aggregate_id`** (`bank_txn_ref`).

Vì sao bắt buộc có key: không key thì round-robin, hai event của **cùng một giao dịch**
rơi hai partition, xử lý **không theo thứ tự** — và Kafka không báo lỗi gì cả. Key theo
giao dịch cho ta thứ tự trong phạm vi một giao dịch, vẫn song song giữa các giao dịch.

**Value** = envelope của row outbox: `event_id`, `bank_code`, `aggregate_type`,
`aggregate_id`, `event_type`, `payload`, `destination`.

`payload` đi **nguyên văn dạng TEXT**, không parse, không re-serialize — nó là byte đã
đóng băng để HMAC khớp.

**Headers:** `event_id`, `traceparent` (Inc 11).

## 6. Cấu hình bắt buộc — và vì sao

Mặc định của docker image sẽ **phá** demo chết-broker.

```yaml
KAFKA_DEFAULT_REPLICATION_FACTOR: 3
KAFKA_MIN_INSYNC_REPLICAS: 2
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2
```

- `RF=3` → mỗi partition 3 bản.
- `min.insync.replicas=2` → còn 2 bản sống thì vẫn **ghi được**; chết 2 broker thì
  producer **bị từ chối** chứ không âm thầm mất dữ liệu.
- `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=3` là cái bị quên nhiều nhất. Topic nội bộ
  `__consumer_offsets` mà RF=1 thì chết đúng broker đó là **consumer group tê liệt**,
  dù topic nghiệp vụ RF=3.
- `unclean.leader.election.enable` giữ **false**: với event tiền, chọn **đúng đắn** hơn
  **sẵn sàng**.

```yaml
spring.kafka:
  producer:
    acks: all
  consumer:
    auto-offset-reset: earliest
    max-poll-records: 1
  listener:
    ack-mode: MANUAL_IMMEDIATE
```

- `acks=all` nghĩa là **"đủ ISR"**, không phải "đủ 3 bản". `acks=1` → leader ack rồi
  chết trước khi replicate → **mất event tiền**.
- `auto-offset-reset: earliest` — chỉ có tác dụng khi group **chưa có offset**, nhưng
  đúng lúc đó nó quyết định group mới có bỏ sót toàn bộ event đang chờ hay không.
  `latest` ở đây là mất tiền.
- `max-poll-records: 1` — mỗi message là một HTTP call, không gom batch.
- `ack-mode: MANUAL_IMMEDIATE` — commit **sau khi** webhook 2xx. Lưu ý Spring đã tự đặt
  `enable.auto.commit=false` và mặc định `BATCH`, **khác mặc định Kafka**.
- `enable.idempotence` mặc định true từ Kafka 3.0: chống **producer retry** tạo bản
  trùng trong log. Nó **không** chống app publish hai lần.

**Retention của DLT phải dài.** Kafka xoá message theo thời gian **dù chưa ai consume**
— mặc định 7 ngày. DLT là nơi giữ bằng chứng để điều tra, nên đặt retention riêng.

## 7. Producer — `dispatchDueEvents()`

Ba transaction tách rời, **không giữ transaction nào qua lời gọi mạng**:

1. **T1** — claim: `SELECT ... WHERE status='PENDING' AND next_attempt_at <= now()
   FOR UPDATE SKIP LOCKED LIMIT n`.
2. **ngoài transaction** — `kafkaTemplate.send()` và **chờ ack**. `send()` bất đồng bộ;
   mark `PUBLISHED` trước khi broker ack là mất message.
3. **T2** — mark `PUBLISHED`. Lỗi publish → tăng `attempt_count`, đẩy `next_attempt_at`,
   giữ `PENDING`.

`SKIP LOCKED` để hai instance relay không bốc trùng row.

## 8. Consumer

Nằm trong `fake-bank`.

> Production sẽ tách thành service riêng (`bank-webhook-dispatcher`) để webhook dồn ứ
> không ảnh hưởng trang thanh toán và để scale riêng. Ở đây để cùng service cho gọn.

Thứ tự trong listener:

1. Deserialize envelope (`ErrorHandlingDeserializer` để message rác không gây loop).
2. **Ký HMAC ngay tại đây**, không ký lúc publish.
3. `POST` tới `destination` với header `Idempotency-Key: event_id`.
4. 2xx → `UPDATE outbox SET status='SENT', sent_at=now()` → `ack.acknowledge()`.
5. Lỗi → ném exception đúng loại, `@RetryableTopic` lo phần còn lại.

### 8.1 Vì sao ký ở consumer, không ký lúc publish

Chữ ký có `timestamp` trong chuỗi ký và Hub chỉ nhận cửa sổ 5 phút. Message nằm trong
retry topic 30 phút rồi mới gửi → **chữ ký quá hạn → 401 → vào DLT sai lý do**.

`payload` vẫn đóng băng từ lúc ghi outbox; chỉ phần chữ ký được tạo mới.

Khoá dùng ở đây là **khoá riêng cho chiều webhook**, khác khoá chiều Hub→Bank
(đã chốt trước, 6 secret mới).

### 8.2 Phân loại lỗi

| Kết quả | Xử lý |
|---|---|
| 2xx | thành công → `SENT` → commit offset |
| **4xx** (trừ 408, 429) | **lỗi vĩnh viễn** → **DLT ngay**, không retry |
| 5xx · 408 · 429 · timeout · connection refused | lỗi tạm → leo thang retry |

Retry một `400 Bad Request` 5 lần là vô nghĩa — chữ ký sai thì lần thứ 5 vẫn sai.
Trùng đúng luật checklist dòng 97.

### 8.3 Retry ladder

`@RetryableTopic` (**non-blocking**): message hỏng được publish sang topic retry,
partition chạy tiếp.

Ngân sách: `10s → 1m → 5m → 30m` ≈ 36 phút. Đủ cho một lần deploy hoặc sự cố ngắn.

Vì sao không **blocking retry** cho cả 36 phút: một message hỏng sẽ **chặn toàn bộ
partition** phía sau nó (head-of-line blocking). Blocking retry chỉ dùng 2–3 lần đầu,
cách nhau vài giây.

> Kafka **không có delayed message**. Độ trễ ở retry topic được Spring làm bằng cách
> **pause partition** cho tới khi `timestamp + delay` tới. Lag ở retry topic cao là
> bình thường.

## 9. Không chọn số lần retry — chọn ngân sách thời gian

Không tồn tại số N hữu hạn nào đảm bảo một cú HTTP tới máy khác thành công. Nên:

- blocking retry: 2–3 lần, vài giây → đỡ blip/restart
- retry topic: ~36 phút → đỡ deploy/sự cố ngắn
- DLT: đỗ, alert, bơm lại khi có người quyết
- **sweeper outbox: lưới cuối, không giới hạn** ← đây mới là "chắc chắn được thực hiện"

## 10. Không chống webhook gửi hai lần — làm cho lần thứ hai vô hại

Không thể biết chắc một message qua mạng đã đến hay chưa (bài toán hai vị tướng). Các
nguồn gửi trùng:

- relay POST xong, crash trước khi `UPDATE SENT`
- consumer gọi webhook xong, crash trước khi commit offset → Kafka phát lại
- rebalance → partition chuyển chủ, message chưa commit được phát lại
- sweeper republish row đã nằm trong Kafka

Kafka + retry topic làm số lần trùng **TĂNG**, không giảm. Nên **idempotency ở Hub là
điều kiện tiên quyết để được dùng Kafka**, không phải việc làm sau:

- `event_id` đi trong header `Idempotency-Key`
- Hub tra bảng, thấy trùng thì **trả 2xx** chứ không xử lý lại
- Hub trả 409/400 cho event đã xử lý → **DLT sẽ đầy những ca đã thành công**

**Exactly-once (EOS) không áp dụng được ở đây.** Transaction của Kafka chỉ cho
Kafka→Kafka. Ta ghi ra HTTP.

## 11. Chia lát và kịch bản verify

### L1 — hạ tầng + luồng thuận

3 broker KRaft, topic 3 partition RF=3, migration `V7`, relay publish, consumer gọi
webhook với manual commit.

| # | Kịch bản | Bằng chứng |
|---|---|---|
| 1 | thanh toán thường | outbox `PENDING→PUBLISHED→SENT`, payment `CAPTURED`, lag về 0 |

### L2 — chết broker, acks, ISR

| # | Kịch bản | Cách gây | Bằng chứng |
|---|---|---|---|
| 2 | chết 1 broker | `docker stop kafka-2` | log `NOT_LEADER_OR_FOLLOWER` + `Shrinking ISR` + webhook **vẫn tới** |
| 3 | chết 2 broker | stop thêm 1 | producer **bị từ chối** (`NOT_ENOUGH_REPLICAS`), row ở lại `PENDING`, không mất gì |

Cơ chế cần giải thích được: mỗi partition có **một leader**, producer chỉ ghi vào
leader → broker chết → controller bầu leader mới từ ISR → producer nhận
`NOT_LEADER_OR_FOLLOWER` → **tự refresh metadata** → ghi vào leader mới.

### L3 — retry topic, DLT, phân loại lỗi

| # | Kịch bản | Cách gây | Bằng chứng |
|---|---|---|---|
| 6 | leo thang retry | `docker stop payment-hub` | message đi `-retry-0→1→2→3`, timestamp cách 10s/1m/5m/30m |
| 7 | DLT | Hub tắt hết ngân sách | message vào `-dlt`, outbox `PARKED` |
| 8 | 4xx không retry | xoay secret webhook của Hub | 401 → **DLT ngay**, không row nào trong retry topic |
| 9 | webhook trùng vô hại | bơm lại message từ DLT | Hub trả **2xx**, payment vẫn `CAPTURED`, không có bản ghi thứ hai |
| 13 | sweeper không nhân bản | kill relay sau publish, trước mark `PUBLISHED` | republish **đúng 1 lần**, không có 180 message |

### L4 — demo học

| # | Kịch bản | Cách gây | Bằng chứng |
|---|---|---|---|
| 4 | auto commit làm **mất** message | bật `enable.auto.commit=true`, Hub trả 500 chậm, kill consumer sau 5s | offset đã commit, message **không bao giờ gửi lại**, outbox mắc `PUBLISHED` mãi |
| 5 | manual commit cứu ca trên | `MANUAL_IMMEDIATE`, kill y hệt | consumer bật lại → message **phát lại** → gửi thành công |
| 10 | `auto.offset.reset` | group mới khi topic đã có 5 message | `latest` → bỏ hết 5; `earliest` → xử lý đủ 5 |
| 11 | rebalance | bật instance thứ 2 | log `Assigned partitions`, 3 partition chia 2-1 |
| 12 | thứ tự theo key | 2 event cùng `bank_txn_ref` | cùng partition, xử lý đúng thứ tự |
| 14 | `max.poll.interval.ms` | webhook mất 40s | consumer **bị đuổi khỏi group** → rebalance liên hoàn. Lưu ý: đây **không phải** `session.timeout.ms` |

## 12. Cố ý không làm

| Thứ | Vì sao |
|---|---|
| Kafka transactions / EOS | chỉ Kafka→Kafka, ta ghi ra HTTP |
| Kafka Streams / ksqlDB | không biến đổi stream; đối soát Inc 10 bằng SQL dễ debug hơn |
| **Schema Registry + Avro/Protobuf** | payload phải đóng băng dạng TEXT để HMAC khớp byte. Avro **đổi byte** ⇒ phá chữ ký. Lý do kỹ thuật, không phải cho gọn |
| Kafka Connect / Debezium (CDC) | là cách **thay thế** outbox tầng ứng dụng; ta tự viết relay vì đó mới là chỗ học |
| ZooKeeper | lỗi thời, dùng KRaft |
| Tiered storage, MirrorMaker, quotas | chưa có bài toán |

## 13. Ghi chú production-vs-here

| Ở đây | Production |
|---|---|
| 3 broker KRaft combined trên 1 máy (~1.5GB RAM) | MSK/cluster riêng, trải 3 AZ, controller tách |
| consumer nằm trong `fake-bank` | service riêng `bank-webhook-dispatcher`, scale độc lập |
| webhook delivery qua Kafka | **SQS** (delay queue + DLQ native) — xem 2.4 |
| secret webhook trong docker-compose env | Secrets Manager |
| lag xem bằng CLI | metric → Prometheus, alert khi lag > N trong M phút |

## 14. Còn treo

- 6 secret riêng cho chiều webhook — đã chốt, chưa implement
- Hub-side `/bank-callbacks/**`: security chain riêng, đối chiếu `amount` + `currency`,
  CAS `WHERE status='IN_PROGRESS'` → `CAPTURED`
- fake-bank vẫn **chưa có test tự động** nào
- `3 broker` đụng tiêu chí "rẻ" §1 → có thể cần profile tắt khi không demo

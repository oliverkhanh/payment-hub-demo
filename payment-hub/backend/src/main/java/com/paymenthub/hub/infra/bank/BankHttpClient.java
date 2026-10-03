package com.paymenthub.hub.infra.bank;

import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.infra.security.HmacSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lớp vận tải DÙNG CHUNG cho mọi adapter ngân hàng: ký HMAC + timeout + retry.
 *
 * Vì sao tách khỏi adapter? Vì ba adapter khác nhau ở ĐỊNH DẠNG (JSON/form, tên tham số,
 * bảng mã lỗi) nhưng GIỐNG nhau ở GIAO THỨC BẢO MẬT và chính sách gọi lại. Nhét HMAC vào
 * từng adapter là copy ba lần một thứ dễ sai nhất trong hệ thống.
 *
 * ──────────────── Chuỗi ký (giống scheme Inc 3, docs/notes/hmac-request-signing.md) ───────
 *   ① METHOD            IN HOA
 *   ② PATH              không gồm query
 *   ③ TIMESTAMP         Unix, GIÂY
 *   ④ IDEMPOTENCY_KEY   rỗng nếu contract của bank đó không có header này — DÒNG VẪN CÒN
 *   ⑤ SHA-256(body)     hex chữ thường, tính trên ĐÚNG BYTE sẽ gửi đi
 *
 * ⭐ Chiều này NGƯỢC với Inc 3: ở Inc 3 Hub là bên XÁC THỰC (Hub ra luật, merchant ký).
 * Ở đây Hub là bên BỊ XÁC THỰC — Bank ra luật, Hub phải ký bằng secret Bank cấp.
 *
 * ⭐ Chữ ký tính trên `body` mà caller truyền vào, và chính byte đó được gửi đi. Không
 * serialize hai lần. Serialize lại để ký là nguồn gốc của lỗi "chữ ký đúng mà vẫn 401"
 * (một bên thêm dấu cách, đổi thứ tự khoá, là chuỗi ký khác ngay).
 */
@Component
public class BankHttpClient {

    private static final Logger log = LoggerFactory.getLogger(BankHttpClient.class);
    private static final SecureRandom JITTER = new SecureRandom();

    public static final String HEADER_KEY         = "X-Hub-Key";
    public static final String HEADER_SIGNATURE   = "X-Hub-Signature";
    public static final String HEADER_TIMESTAMP   = "X-Hub-Timestamp";
    public static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private final HubBankProperties banks;
    private final HmacSigner signer;

    /** Một RestClient cho mỗi bank: timeout là của RIÊNG từng bank, không dùng chung. */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public BankHttpClient(HubBankProperties banks, HmacSigner signer) {
        this.banks = banks;
        this.signer = signer;
    }

    /**
     * ⭐ BA kết cục của tầng vận tải, KHÔNG phải hai.
     *
     * Gộp NOT_SENT với NO_ANSWER là lỗi nghiêm trọng: nó biến "biết chắc bank không nhận
     * gì" thành "không biết bank nhận gì", và hậu quả là một payment bị treo IN_PROGRESS
     * chờ đối soát trong khi chẳng có gì để đối soát.
     */
    public enum Transport {
        /** Có câu trả lời HTTP (kể cả 4xx/5xx — đó VẪN là câu trả lời). */
        ANSWERED,

        /**
         * Request CHƯA HỀ ra khỏi máy Hub: connection refused, DNS không phân giải,
         * connect timeout (bắt tay TCP chưa xong). Tiền chắc chắn còn nguyên.
         */
        NOT_SENT,

        /**
         * Đã gửi đi rồi mà không đọc được câu trả lời: read timeout, connection reset
         * giữa đường. Bank CÓ THỂ đã xử lý. Đây là ca "in-doubt".
         */
        NO_ANSWER
    }

    /**
     * Kết quả THÔ của cú gọi. Chưa dịch sang BankOutcome — việc dịch là của adapter,
     * vì chỉ adapter biết "51" hay "9527" của bank mình nghĩa là gì.
     */
    public record Response(Integer status, String body, Transport transport, String failureReason) {
        public boolean answered() { return transport == Transport.ANSWERED; }
        public boolean notSent()  { return transport == Transport.NOT_SENT; }
        public boolean noAnswer() { return transport == Transport.NO_ANSWER; }
        public boolean ok()       { return status != null && status >= 200 && status < 300; }
        public boolean serverErr(){ return status != null && status >= 500; }
    }

    public Response post(String bankCode, String url, MediaType contentType,
                         byte[] body, String idempotencyHeader) {

        HubBankProperties.BankInfo bank = banks.require(bankCode);
        int maxSends = bank.retries() + 1;          // lần đầu + số lần gửi lại

        Response last = null;
        for (int send = 1; send <= maxSends; send++) {

            // ⭐ Ký LẠI mỗi lần gửi vì timestamp đổi (nếu dùng lại chữ ký cũ thì sau 5 phút
            // Bank sẽ từ chối vì quá cửa sổ chống replay). Nhưng Idempotency-Key thì
            // TUYỆT ĐỐI GIỮ NGUYÊN — đó là thứ bảo đảm Bank không trừ tiền hai lần.
            last = sendOnce(bank, bankCode, url, contentType, body, idempotencyHeader);

            // Gửi lại được cho cả NOT_SENT và NO_ANSWER: khoá idempotency GIỮ NGUYÊN nên
            // bank tự dedup. 4xx thì không — request của ta sai, gửi lại vẫn sai.
            boolean worthRetrying = !last.answered() || last.serverErr();
            if (!worthRetrying) {
                return last;
            }
            if (send < maxSends) {
                // 4xx KHÔNG retry: request của ta sai, gửi lại y nguyên vẫn sai.
                log.warn("[{}] lần gửi {}/{} chưa có kết quả dứt khoát ({}), sẽ thử lại",
                        bankCode, send, maxSends,
                        last.answered() ? "HTTP " + last.status()
                                        : last.transport() + ":" + last.failureReason());
                sleepBackoff(send);
            }
        }
        return last;
    }

    private Response sendOnce(HubBankProperties.BankInfo bank, String bankCode, String url,
                              MediaType contentType, byte[] body, String idempotencyHeader) {

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String path = URI.create(url).getRawPath();
        String canonical = signer.canonicalString("POST", path, timestamp, idempotencyHeader, body);
        String signature = signer.sign(bank.apiSecret(), canonical);

        try {
            var spec = client(bankCode, bank).post()
                    .uri(url)
                    .contentType(contentType)
                    .header(HEADER_KEY, bank.apiKey())
                    .header(HEADER_TIMESTAMP, timestamp)
                    .header(HEADER_SIGNATURE, signature);

            // "Sự vắng mặt cũng là thông tin được ký": không có header thì dòng ④ rỗng,
            // và ở đây cũng KHÔNG được gửi header rỗng — gửi rỗng là một chuỗi ký khác.
            if (idempotencyHeader != null) {
                spec = spec.header(HEADER_IDEMPOTENCY, idempotencyHeader);
            }

            // exchange(): KHÔNG áp status handler mặc định ⇒ 4xx/5xx về đây dưới dạng
            // giá trị chứ không phải exception. Ta cần thế, vì "Bank trả 422" là một
            // CÂU TRẢ LỜI (dứt khoát), còn exception là "không có câu trả lời" — hai
            // chuyện khác nhau hoàn toàn và không được trộn vào một nhánh catch.
            return spec.body(body).exchange((request, response) ->
                    new Response(response.getStatusCode().value(),
                            response.bodyTo(String.class), Transport.ANSWERED, null));

        } catch (Exception problem) {
            Transport transport = classify(problem);
            log.warn("[{}] gọi {} thất bại ({}): {}", bankCode, path, transport,
                    problem.getClass().getSimpleName() + ": " + problem.getMessage());
            return new Response(null, null, transport, problem.getClass().getSimpleName());
        }
    }

    /**
     * ⭐ Request có ra khỏi máy này hay không?
     *
     * Chỉ ba loại exception CHỨNG MINH ĐƯỢC là "chưa gửi": chưa nối được TCP thì không
     * byte nào đi đâu cả. Mọi thứ khác — kể cả exception ta chưa từng gặp — phải về
     * NO_ANSWER.
     *
     * Hướng mặc định này là CÓ CHỦ ĐÍCH và không được đảo: đoán sai thành NOT_SENT nghĩa
     * là Hub mở payment ra cho thử lại trong khi bank có thể đang giữ một giao dịch
     * ⇒ khách trả hai lần. Đoán sai thành NO_ANSWER chỉ khiến payment chờ đối soát
     * ⇒ chậm, nhưng không mất tiền. Khi không chắc thì chọn cái chậm.
     */
    private static Transport classify(Throwable error) {
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConnectException               // bank chết / cổng đóng
                    || t instanceof UnknownHostException    // DNS không phân giải
                    || t instanceof HttpConnectTimeoutException) {  // bắt tay TCP quá hạn
                return Transport.NOT_SENT;
            }
        }
        return Transport.NO_ANSWER;
    }

    /**
     * Một RestClient cho mỗi bank: timeout là của RIÊNG từng bank, không dùng chung.
     *
     * Dùng JdkClientHttpRequestFactory (không phải SimpleClientHttpRequestFactory) vì nó
     * ném HAI loại exception KHÁC NHAU cho hai tình huống khác nhau:
     *   connect quá hạn -> HttpConnectTimeoutException   (chưa gửi gì)
     *   read quá hạn    -> HttpTimeoutException          (đã gửi, không có trả lời)
     * SimpleClientHttpRequestFactory ném cùng một SocketTimeoutException cho cả hai, chỉ
     * khác chuỗi message — phân biệt bằng message là thứ sẽ vỡ im lặng khi nâng JDK.
     */
    private RestClient client(String bankCode, HubBankProperties.BankInfo bank) {
        return clients.computeIfAbsent(bankCode, code -> {
            HttpClient jdk = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(bank.connectTimeout()))
                    .build();
            var factory = new JdkClientHttpRequestFactory(jdk);
            factory.setReadTimeout(Duration.ofMillis(bank.readTimeout()));
            log.info("[{}] RestClient: connect={}ms read={}ms retry={}",
                    code, bank.connectTimeout(), bank.readTimeout(), bank.retries());
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    /**
     * Exponential backoff + JITTER. Jitter không phải để cho đẹp: nếu 500 request cùng
     * timeout rồi cùng ngủ đúng 200ms, chúng sẽ cùng tỉnh và đập vào Bank một lúc
     * (thundering herd) — đúng lúc Bank đang yếu nhất.
     */
    private void sleepBackoff(int sendNo) {
        long delay = (long) (200 * Math.pow(2, sendNo - 1)) + JITTER.nextInt(100);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.loglens.sut;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 부하 특성을 파라미터로 조절할 수 있는 엔드포인트. 비즈니스 로직은 없다.
 *
 * <p>응답의 {@code virtual} 은 요청을 처리한 스레드가 가상 스레드인지다.
 * SPRING_THREADS_VIRTUAL_ENABLED 설정이 실제로 적용됐는지 이 값으로 확인한다.
 */
@RestController
public class LoadController {

    static final long MAX_DELAY_MS = 30_000;
    static final long MAX_CPU_N = 1_000_000;
    static final int MAX_PAYLOAD_KB = 10_240;

    // /payload 응답 본문의 재료. 요청마다 kb 만큼 할당하지 않고 이 조각을 반복해 쓴다.
    // (10MB 응답이 동시에 수백 개 몰려도 힙이 터지지 않게.) 압축이 걸려도 크기가 줄지 않도록 난수로 채운다.
    private static final byte[] CHUNK = new byte[64 * 1024];

    static {
        new Random(42).nextBytes(CHUNK);
    }

    record StatusResponse(String status) {}

    record EchoResponse(boolean ok, boolean virtual) {}

    record DelayResponse(long ms, boolean virtual) {}

    record CpuResponse(long n, String result, boolean virtual) {}

    record ErrorResponse(String error) {}

    /** 헬스체크용. 즉시 200. */
    @GetMapping("/health")
    public StatusResponse health() {
        return new StatusResponse("UP");
    }

    /** 가장 가벼운 경로. 프레임워크 자체 오버헤드의 기준선. */
    @GetMapping("/echo")
    public EchoResponse echo() {
        return new EchoResponse(true, isVirtual());
    }

    /**
     * I/O 대기 흉내. 요청 스레드를 ms 동안 붙잡는다.
     *
     * <p>플랫폼 스레드(기본)에서는 톰캣 스레드 하나를 그대로 점유하므로
     * 동시 요청이 server.tomcat.threads.max 를 넘으면 대기열이 생긴다.
     * 가상 스레드에서는 sleep 동안 캐리어 스레드를 내려놓으므로 그 한계가 없다.
     * (synchronized 블록 안에서 sleep 하면 Java 21 에서는 캐리어가 고정(pinning)되니 여기서는 쓰지 않는다.)
     */
    @GetMapping("/delay")
    public DelayResponse delay(@RequestParam(defaultValue = "100") long ms) throws InterruptedException {
        requireRange("ms", ms, 0, MAX_DELAY_MS);
        Thread.sleep(ms);
        return new DelayResponse(ms, isVirtual());
    }

    /**
     * CPU 바운드 작업. 32바이트에 SHA-256 을 n 번 연쇄 적용한다.
     * 결과를 응답에 넣어 JIT 가 계산을 없애지 못하게 한다.
     */
    @GetMapping("/cpu")
    public CpuResponse cpu(@RequestParam(defaultValue = "1000") long n) throws NoSuchAlgorithmException {
        requireRange("n", n, 1, MAX_CPU_N);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = new byte[32];
        for (long i = 0; i < n; i++) {
            hash = sha256.digest(hash);
        }
        return new CpuResponse(n, HexFormat.of().formatHex(hash, 0, 8), isVirtual());
    }

    /** kb KiB 크기의 바이너리 본문. 네트워크 대역폭 영향을 본다. */
    @GetMapping("/payload")
    public void payload(@RequestParam(defaultValue = "100") long kb, HttpServletResponse response) throws IOException {
        requireRange("kb", kb, 0, MAX_PAYLOAD_KB);
        long remaining = kb * 1024;
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(remaining);
        OutputStream out = response.getOutputStream();
        while (remaining > 0) {
            int len = (int) Math.min(CHUNK.length, remaining);
            out.write(CHUNK, 0, len);
            remaining -= len;
        }
    }

    // ---- 파라미터 검증 --------------------------------------------------------

    static final class InvalidParameterException extends RuntimeException {
        InvalidParameterException(String message) {
            super(message);
        }
    }

    private static void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new InvalidParameterException(name + " must be between " + min + " and " + max + ": " + value);
        }
    }

    @ExceptionHandler(InvalidParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalid(InvalidParameterException e) {
        return new ErrorResponse(e.getMessage());
    }

    /** ?ms=abc 처럼 숫자가 아닌 값. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return new ErrorResponse(e.getName() + " must be an integer: " + e.getValue());
    }

    private static boolean isVirtual() {
        return Thread.currentThread().isVirtual();
    }
}

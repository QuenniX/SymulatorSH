package pl.smarthome.platform.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiting oparty o Caffeine cache w pamieci JVM.
 *
 * <p>Zasady:</p>
 * <ul>
 *   <li>Uzytkownicy bez klucza: <b>100 write requests/dzien per IP</b></li>
 *   <li>Uzytkownicy z kluczem: <b>1000 write requests/dzien per klucz</b>
 *       (albo cokolwiek jest w polu daily_limit encji ApiKey)</li>
 *   <li>Reset o polnocy UTC (nie lokalnej strefy - dla spojnosci)</li>
 *   <li>Licznik tylko dla write ops (POST/PUT/DELETE) - GET sa free</li>
 * </ul>
 *
 * <p><b>Znane ograniczenie:</b> licznik zyje tylko w pamieci JVM. Restart backendu
 * = zerowanie liczników. Dla srodowiska produkcyjnego rekomendowany Redis albo
 * inne cache trwale. Dla pracy magisterskiej wystarczy - jasno zaznaczone.</p>
 */
@Service
@Slf4j
public class RateLimitService {

    private static final int DEFAULT_ANONYMOUS_LIMIT = 100;

    /**
     * Cache: identyfikator (klucz API albo IP) -> licznik dzienny.
     * TTL 24h zeby wpisy nie zbieraly sie w nieskonczonosc jesli ktos wraca po dniu.
     * Reset licznika o polnocy UTC nastepuje pryzez sprawdzenie daty ostatniego uzycia -
     * jesli inna, zerujemy licznik.
     */
    private final Cache<String, DailyCounter> counters = Caffeine.newBuilder()
            .maximumSize(100_000)  // max 100k unikalnych IP/kluczy dziennie
            .expireAfterAccess(Duration.ofHours(25))  // trochę wiecej niz 24h dla bezpieczenstwa
            .build();

    /**
     * Sprawdza czy klient moze wykonac kolejny request.
     * Jesli tak - inkrementuje licznik i zwraca stan.
     * Jesli nie - zwraca stan z remaining=0.
     */
    public LimitStatus checkAndIncrement(String identifier, int limit) {
        DailyCounter counter = counters.get(identifier, k -> new DailyCounter());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        synchronized (counter) {
            // Jesli licznik jest z wczoraj, zresetuj
            if (!today.equals(counter.date)) {
                counter.date = today;
                counter.count.set(0);
            }

            int current = counter.count.get();
            if (current >= limit) {
                // Przekroczono - nie inkrementuj, zwroc info
                return new LimitStatus(limit, current, 0, resetAtUtc(), true);
            }

            int newCount = counter.count.incrementAndGet();
            return new LimitStatus(limit, newCount, limit - newCount, resetAtUtc(), false);
        }
    }

    /**
     * Podglada stan bez inkrementacji - dla read endpoints (zeby ustawic nagłówki
     * X-RateLimit-* bez zliczania read requestow).
     */
    public LimitStatus peek(String identifier, int limit) {
        DailyCounter counter = counters.getIfPresent(identifier);
        if (counter == null) {
            return new LimitStatus(limit, 0, limit, resetAtUtc(), false);
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        synchronized (counter) {
            int current = today.equals(counter.date) ? counter.count.get() : 0;
            return new LimitStatus(limit, current, Math.max(0, limit - current), resetAtUtc(), current >= limit);
        }
    }

    /** Domyślny limit dla klientów bez klucza API. */
    public int anonymousLimit() {
        return DEFAULT_ANONYMOUS_LIMIT;
    }

    /** Timestamp reset licznika (nastepna polnoc UTC) - do naglowka X-RateLimit-Reset. */
    private Instant resetAtUtc() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // ==========================================================
    //  Struktury pomocnicze
    // ==========================================================

    /** Licznik dzienny dla jednego klienta. */
    private static class DailyCounter {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        final AtomicInteger count = new AtomicInteger(0);
    }

    /**
     * Stan limitu do przekazania klientowi w naglowkach HTTP i ewentualnym errorze 429.
     */
    public record LimitStatus(
            int limit,
            int used,
            int remaining,
            Instant resetAt,
            boolean exceeded
    ) {
        /** Sekundy do restu - do naglowka Retry-After. */
        public long secondsUntilReset() {
            return Math.max(0, Duration.between(Instant.now(), resetAt).getSeconds());
        }
    }
}

package io.tradevision.gateway.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** VWAP, realized volatility, flow, spread, depth imbalance and Polymarket basis over a window (minutes). */
    @GetMapping("/markets/{id}")
    public AnalyticsService.MarketAnalytics market(@PathVariable long id, @RequestParam(defaultValue = "60") int window) {
        var a = analytics.market(id, window);
        if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no market " + id);
        return a;
    }

    /** Last-hour metrics for every market, for a sortable scanner table. */
    @GetMapping("/scanner")
    public List<AnalyticsService.ScannerRow> scanner() {
        return analytics.scanner();
    }

    @GetMapping("/leaderboard")
    public AnalyticsService.Leaderboard leaderboard(@RequestParam(defaultValue = "20") int limit,
                                                    @RequestParam(defaultValue = "false") boolean bots) {
        return analytics.leaderboard(limit, bots);
    }
}

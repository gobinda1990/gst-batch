package gov.com.ai.webapp.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import gov.com.ai.webapp.domain.OfficeMonthlyRevenue;
import gov.com.ai.webapp.exception.RevenueBatchException;
import gov.com.ai.webapp.repository.OfficeRevenueBatchRepository.GrowthUpdate;
import gov.com.ai.webapp.service.OfficeRevenueGrowthCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeRevenueTransactionalWorker {

	private final OfficeRevenueBatchRepository repo;
	private final OfficeRevenueGrowthCalculator calc;

	/**
	 * One atomic unit: aggregate -> merge -> remove offices that disappeared ->
	 * growth. Everything is batched, so the cost no longer grows with a query per
	 * office.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Result process(String p) {

		long t = System.nanoTime();

		// 1) aggregate the period per office
		List<OfficeMonthlyRevenue> aggregated = repo.aggregate(p);

		if (aggregated.isEmpty()) {
			throw new RevenueBatchException("No mapped office aggregates for " + p);
		}

		log.info("[1/4] aggregated period={} offices={} in {} ms", p, aggregated.size(), lap(t));
		t = System.nanoTime();

		Set<String> current = aggregated.stream().map(OfficeMonthlyRevenue::stJuri).collect(Collectors.toSet());

		// offices already stored for the period BEFORE the merge - needed to find stale
		// ones
		Set<String> existing = new HashSet<>(repo.offices(p));

		// 2) merge
		int merged = repo.mergeAll(aggregated);

		log.info("[2/4] merged period={} offices={} in {} ms", p, merged, lap(t));
		t = System.nanoTime();
		
		// aggregation reads GST_DEALER_MASTER_WBCOMTAX, so it could delete rows that
		// had just been merged.)
		List<String> stale = existing.stream().filter(j -> !current.contains(j)).sorted().toList();

		int staleDeleted = repo.deleteOffices(p, stale);

		if (staleDeleted > 0) {
			log.warn("[3/4] removed {} stale offices for period={}: {}", staleDeleted, p, stale);
		} else {
			log.info("[3/4] no stale offices for period={}", p);
		}

		t = System.nanoTime();

		// 4) growth: ONE history query for all offices, ONE batch update
		Map<String, List<OfficeMonthlyRevenue>> history = repo.historyForPeriod(p).stream()
				.collect(Collectors.groupingBy(OfficeMonthlyRevenue::stJuri, LinkedHashMap::new, Collectors.toList()));

		List<GrowthUpdate> updates = new ArrayList<>(current.size());

		for (String j : new TreeSet<>(current)) {

			List<OfficeMonthlyRevenue> h = history.getOrDefault(j, List.of());

			OfficeMonthlyRevenue cur = h.stream().filter(x -> p.equals(x.retPeriod())).findFirst().orElse(null);

			if (cur == null) {
				// we just merged this office, so it must exist - never skip it silently
				throw new RevenueBatchException("Merged office " + j + "/" + p + " not found for growth calculation");
			}

			var g = calc.calculate(cur, h);

			updates.add(new GrowthUpdate(j, g.momOutputGrowth(), g.yoyOutputGrowth(), g.momCashGrowth(),
					g.yoyCashGrowth(), g.avgOutputTax3m(), g.avgOutputTax6m(), g.avgOutputTax12m(), g.avgCashTax3m(),
					g.avgCashTax6m(), g.avgCashTax12m(), g.growthTrend(), g.growthStatus()));
		}

		int growth = repo.updateGrowthBatch(p, updates);

		log.info("[4/4] growth period={} offices={} in {} ms", p, growth, lap(t));

		return new Result(aggregated.size(), merged, staleDeleted, growth);
	}

	private static long lap(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	public record Result(int offices, int merged, int staleDeleted, int growthUpdated) {
	}
}
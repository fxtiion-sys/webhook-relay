-- Summarize webhook load test results by isolated event type.
-- Latency is measured from event creation to delivery update.
-- Only successful deliveries are included in latency calculations.

SELECT
    e.event_type,
    COUNT(DISTINCT e.id) AS events,
    COUNT(d.id) AS deliveries,
    COUNT(*) FILTER (
        WHERE d.status = 'SUCCEEDED'
    ) AS succeeded,
    COUNT(*) FILTER (
        WHERE d.status = 'PENDING'
    ) AS pending,
    COUNT(*) FILTER (
        WHERE d.status = 'IN_PROGRESS'
    ) AS in_progress,
    COUNT(*) FILTER (
        WHERE d.status = 'DEAD'
    ) AS dead,
    COUNT(*) FILTER (
        WHERE d.attempt_count > 1
    ) AS retried,
    ROUND(AVG(
                  EXTRACT(EPOCH FROM (d.updated_at - e.created_at)) * 1000
          ) FILTER (
        WHERE d.status = 'SUCCEEDED'
    )::numeric, 2) AS avg_ms,
    ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM
            (d.updated_at - e.created_at)) * 1000
    ) FILTER (
        WHERE d.status = 'SUCCEEDED'
    )::numeric, 2) AS p50_ms,
    ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM
            (d.updated_at - e.created_at)) * 1000
    ) FILTER (
        WHERE d.status = 'SUCCEEDED'
    )::numeric, 2) AS p95_ms,
    ROUND(MAX(
                  EXTRACT(EPOCH FROM (d.updated_at - e.created_at)) * 1000
          ) FILTER (
        WHERE d.status = 'SUCCEEDED'
    )::numeric, 2) AS max_ms
FROM events e
         LEFT JOIN deliveries d ON d.event_id = e.id
WHERE e.event_type LIKE 'load-test.%'
GROUP BY e.event_type
ORDER BY MAX(e.created_at) DESC;
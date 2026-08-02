UPDATE package_requests pr
SET status = 'OPEN',
    updated_at = CURRENT_TIMESTAMP
WHERE pr.status = 'NEGOTIATING'
  AND NOT EXISTS (
      SELECT 1
      FROM negotiation_threads nt
      WHERE nt.package_request_id = pr.id
        AND nt.status IN ('OPEN', 'AWAITING_TRIP', 'AWAITING_PAYMENT')
        AND nt.deleted_at IS NULL
  );

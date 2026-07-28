from datetime import datetime, timezone

from odoo import api, fields, models


class FoodRateLimit(models.Model):
    _name = "food.rate.limit"
    _description = "Food Ordering Rate Limit Counter"
    _order = "id"
    _rec_name = "identifier"

    bucket = fields.Char(required=True, index=True)
    identifier = fields.Char(required=True, index=True)
    window_start = fields.Datetime(required=True, index=True)
    count = fields.Integer(default=0, required=True)

    _bucket_identifier_window_unique = models.Constraint(
        "UNIQUE(bucket, identifier, window_start)",
        "Only one counter row per bucket/identifier/window.",
    )

    @api.model
    def _hit(self, bucket, identifier, limit, window_seconds):
        """Atomically increment the counter for the current window and
        return True if the caller is still within the allowed limit.

        Uses a row lock (or a fresh row) so concurrent requests from the
        same identifier cannot race past the limit.
        """
        if not identifier:
            return True
        window_seconds = max(1, int(window_seconds))
        epoch = int(datetime.now(timezone.utc).timestamp())
        window_epoch = epoch - (epoch % window_seconds)
        window_start = fields.Datetime.to_string(
            datetime.fromtimestamp(window_epoch, tz=timezone.utc).replace(tzinfo=None)
        )
        self.env.cr.execute(
            """
                INSERT INTO food_rate_limit (bucket, identifier, window_start, count, create_date, write_date)
                     VALUES (%s, %s, %s, 1, now() at time zone 'utc', now() at time zone 'utc')
                ON CONFLICT (bucket, identifier, window_start)
                     DO UPDATE SET count = food_rate_limit.count + 1,
                                    write_date = now() at time zone 'utc'
                  RETURNING count
            """,
            (bucket, identifier, window_start),
        )
        current_count = self.env.cr.fetchone()[0]
        return current_count <= limit

    @api.autovacuum
    def _gc_rate_limits(self):
        cutoff = fields.Datetime.subtract(fields.Datetime.now(), hours=6)
        self.search([("window_start", "<", cutoff)]).unlink()

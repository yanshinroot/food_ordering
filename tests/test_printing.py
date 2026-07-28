from odoo import fields
from odoo.exceptions import UserError
from odoo.tests import tagged

from .common import FoodOrderingCommon


@tagged("post_install", "-at_install", "food_ordering")
class TestPrinting(FoodOrderingCommon):
    def test_pairing_claim_creates_hashed_device(self):
        pairing = self.env["food.device.pairing"].create({"role": "cashier"})
        device, raw_key = pairing._claim("Front Counter", "uuid-1", "1.0.0")
        self.assertTrue(pairing.used)
        self.assertEqual(pairing.device_id, device)
        self.assertTrue(device.device_key_hash)
        self.assertTrue(raw_key)
        found = self.env["food.printer.device"]._find_by_raw_key(raw_key)
        self.assertEqual(found, device)
        wrong = self.env["food.printer.device"]._find_by_raw_key("not-the-real-key")
        self.assertFalse(wrong)

    def test_pairing_single_use(self):
        pairing = self.env["food.device.pairing"].create({"role": "kitchen"})
        pairing._claim("Kitchen Tablet", "uuid-2", "1.0.0")
        with self.assertRaises(UserError):
            pairing._claim("Kitchen Tablet 2", "uuid-3", "1.0.0")

    def test_pairing_expiry(self):
        pairing = self.env["food.device.pairing"].create({
            "role": "cashier", "expires_at": fields.Datetime.subtract(fields.Datetime.now(), minutes=1),
        })
        with self.assertRaises(UserError):
            pairing._claim("Late device", "uuid-4", "1.0.0")

    def test_revoked_device_not_found_by_key(self):
        pairing = self.env["food.device.pairing"].create({"role": "cashier"})
        device, raw_key = pairing._claim("Register 1", "uuid-5", "1.0.0")
        device.action_revoke()
        self.assertFalse(self.env["food.printer.device"]._find_by_raw_key(raw_key))

    def test_print_job_enqueued_on_accept(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        jobs = self.env["food.print.job"].search([("order_id", "=", order.id)])
        self.assertEqual(set(jobs.mapped("target")), {"cashier", "kitchen"})
        self.assertTrue(all(job.state == "queued" for job in jobs))

    def test_reprint_bumps_template_version_and_logs_event(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        job = self.env["food.print.job"].search([("order_id", "=", order.id), ("target", "=", "kitchen")])
        self.assertEqual(job.template_version, 1)
        order.action_food_reprint("kitchen")
        job.invalidate_recordset()
        self.assertEqual(job.template_version, 2)
        self.assertTrue(job.is_reprint)
        self.assertIn("reprint_requested", order.food_event_ids.mapped("event_type"))

    def test_print_job_retry_and_dead_letter(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        job = self.env["food.print.job"].search([("order_id", "=", order.id), ("target", "=", "cashier")])
        job.max_attempts = 2
        job.attempts = 2
        job._mark_failed("printer offline")
        self.assertEqual(job.state, "dead_letter")
        job.action_retry()
        self.assertEqual(job.state, "queued")
        self.assertEqual(job.attempts, 0)

    def test_print_ack_idempotent(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        pairing = self.env["food.device.pairing"].create({"role": "cashier"})
        device, _raw_key = pairing._claim("Register", "uuid-6", "1.0.0")
        job = self.env["food.print.job"].search([("order_id", "=", order.id), ("target", "=", "cashier")])
        job.write({"state": "claimed", "device_id": device.id})
        job.action_ack(True, actor_device=device)
        self.assertEqual(job.state, "printed")
        # Acking success again (a retried network call) must stay safe.
        job.action_ack(True, actor_device=device)
        self.assertEqual(job.state, "printed")

    def test_print_job_claim_query_is_scoped_and_locked(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        self.env.cr.execute(
            """
                SELECT id FROM food_print_job
                 WHERE target = 'cashier' AND state = 'queued'
                 ORDER BY create_date ASC, id ASC
                 FOR UPDATE SKIP LOCKED
                 LIMIT 10
            """
        )
        rows = self.env.cr.fetchall()
        self.assertEqual(len(rows), 1)
        kitchen_job = self.env["food.print.job"].search(
            [("order_id", "=", order.id), ("target", "=", "kitchen")]
        )
        self.assertNotIn(kitchen_job.id, [row[0] for row in rows])

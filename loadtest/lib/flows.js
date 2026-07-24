// Shared setup + workload flows for every script in loadtest/. Each entrypoint script
// (peak-load.js, spike-load.js, soak-load.js, ...) imports from here and supplies its own
// `options.scenarios` stage shape — the traffic mix and what "success" looks like per flow
// stays identical across all of them, so a result is comparable between test shapes instead
// of secretly exercising different code paths.
//
// Traffic mix (see mixedTraffic below), same across every script that uses it:
//   - browse            (45%) anonymous catalog reads — no auth overhead
//   - order_journey     (30%) full new-customer flow: signup, login, order, track, cancel
//   - repeat_login      (15%) an existing customer logging in and checking their orders
//   - admin_ops         (10%) admin dashboard-style reads (all orders, notification log)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// ---- metrics, broken out per flow so the summary shows which one degrades first ----
export const browseDuration = new Trend('flow_browse_duration', true);
export const orderJourneyDuration = new Trend('flow_order_journey_duration', true);
export const repeatLoginDuration = new Trend('flow_repeat_login_duration', true);
export const adminOpsDuration = new Trend('flow_admin_ops_duration', true);

export const orderCreated = new Counter('orders_created');
export const orderRateLimited = new Counter('orders_rate_limited_429'); // expected at high per-customer rps, not a failure
export const unexpectedErrors = new Rate('unexpected_errors'); // 5xx / connection failures only — the real "it broke" signal

// ---- shared setup: one admin session + one high-stock product, created once for the whole run ----
export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/users/auth/login`,
    JSON.stringify({ email: 'admin@example.com', password: 'Admin@12345' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(loginRes, { 'setup: admin login 200': (r) => r.status === 200 });
  const adminToken = loginRes.json('accessToken');

  const productRes = http.post(
    `${BASE_URL}/products`,
    JSON.stringify({
      sku: `LOADTEST-${Date.now()}`,
      name: 'k6 Load Test Product',
      description: 'Created by a loadtest/ script — safe to delete',
      price: 24.99,
      categoryId: null,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` } }
  );
  check(productRes, { 'setup: product created 201': (r) => r.status === 201 });
  const productId = productRes.json('id');

  const stockRes = http.post(
    `${BASE_URL}/inventory/${productId}/stock`,
    JSON.stringify({ quantityOnHand: 10000000 }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` } }
  );
  check(stockRes, { 'setup: stock set 200': (r) => r.status === 200 });

  // A small pool of pre-existing customers for the repeat_login flow, distinct from the
  // fresh signups order_journey generates on every iteration.
  const repeatCustomers = [];
  for (let i = 0; i < 10; i++) {
    const email = `k6-repeat-${Date.now()}-${i}@example.com`;
    const password = 'Customer@12345';
    const createRes = http.post(
      `${BASE_URL}/users`,
      JSON.stringify({ email, password, firstName: 'K6', lastName: `Repeat${i}`, roles: ['CUSTOMER'] }),
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` } }
    );
    if (createRes.status === 201) {
      repeatCustomers.push({ email, password, customerId: createRes.json('id') });
    }
  }

  console.log(`setup complete: productId=${productId}, repeatCustomers=${repeatCustomers.length}`);
  return { adminToken, productId, repeatCustomers };
}

// ---- flow implementations ----

export function browse(data) {
  const start = Date.now();
  const listRes = http.get(`${BASE_URL}/products?page=0&size=20`);
  check(listRes, { 'browse: list 200': (r) => r.status === 200 });
  flagIfUnexpected(listRes);

  const productRes = http.get(`${BASE_URL}/products/${data.productId}`);
  check(productRes, { 'browse: get by id 200': (r) => r.status === 200 });
  flagIfUnexpected(productRes);

  browseDuration.add(Date.now() - start);
}

export function orderJourney(data) {
  const start = Date.now();
  const email = `k6-${__VU}-${__ITER}-${Date.now()}@example.com`;
  const password = 'Customer@12345';

  const createRes = http.post(
    `${BASE_URL}/users`,
    JSON.stringify({ email, password, firstName: 'K6', lastName: 'Journey', roles: ['CUSTOMER'] }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.adminToken}` } }
  );
  check(createRes, { 'order_journey: signup 201': (r) => r.status === 201 });
  flagIfUnexpected(createRes);
  if (createRes.status !== 201) return;
  const customerId = createRes.json('id');

  const loginRes = http.post(
    `${BASE_URL}/users/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(loginRes, { 'order_journey: login 200': (r) => r.status === 200 });
  flagIfUnexpected(loginRes);
  if (loginRes.status !== 200) return;
  const customerToken = loginRes.json('accessToken');
  const authHeader = { 'Content-Type': 'application/json', Authorization: `Bearer ${customerToken}` };

  const orderRes = http.post(
    `${BASE_URL}/orders`,
    JSON.stringify({ customerId, items: [{ productId: data.productId, quantity: 1 }] }),
    { headers: authHeader }
  );
  if (orderRes.status === 429) {
    orderRateLimited.add(1);
  } else {
    check(orderRes, { 'order_journey: place order 201': (r) => r.status === 201 });
    flagIfUnexpected(orderRes);
    if (orderRes.status === 201) orderCreated.add(1);
  }

  if (orderRes.status === 201) {
    const orderId = orderRes.json('id');

    const getRes = http.get(`${BASE_URL}/orders/${orderId}`, { headers: authHeader });
    check(getRes, { 'order_journey: get order 200': (r) => r.status === 200 });
    flagIfUnexpected(getRes);

    const listMineRes = http.get(
      `${BASE_URL}/orders?customerId=${customerId}&page=0&size=20`,
      { headers: authHeader }
    );
    check(listMineRes, { 'order_journey: list my orders 200': (r) => r.status === 200 });
    flagIfUnexpected(listMineRes);

    const cancelRes = http.del(`${BASE_URL}/orders/${orderId}?reason=k6+load+test`, null, { headers: authHeader });
    check(cancelRes, { 'order_journey: cancel 204 or 409': (r) => r.status === 204 || r.status === 409 });
    flagIfUnexpected(cancelRes);
  }

  orderJourneyDuration.add(Date.now() - start);
}

export function repeatLogin(data) {
  if (data.repeatCustomers.length === 0) return;
  const start = Date.now();
  const customer = data.repeatCustomers[__VU % data.repeatCustomers.length];

  const loginRes = http.post(
    `${BASE_URL}/users/auth/login`,
    JSON.stringify({ email: customer.email, password: customer.password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(loginRes, { 'repeat_login: login 200': (r) => r.status === 200 });
  flagIfUnexpected(loginRes);
  if (loginRes.status !== 200) return;
  const token = loginRes.json('accessToken');

  const listRes = http.get(
    `${BASE_URL}/orders?customerId=${customer.customerId}&page=0&size=20`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  check(listRes, { 'repeat_login: list orders 200': (r) => r.status === 200 });
  flagIfUnexpected(listRes);

  repeatLoginDuration.add(Date.now() - start);
}

export function adminOps(data) {
  const start = Date.now();
  const authHeader = { Authorization: `Bearer ${data.adminToken}` };

  const ordersRes = http.get(`${BASE_URL}/orders?page=0&size=50`, { headers: authHeader });
  check(ordersRes, { 'admin_ops: list all orders 200': (r) => r.status === 200 });
  flagIfUnexpected(ordersRes);

  const emailsRes = http.get(`${BASE_URL}/notifications/emails?page=0&size=20`, { headers: authHeader });
  check(emailsRes, { 'admin_ops: notification log 200': (r) => r.status === 200 });
  flagIfUnexpected(emailsRes);

  adminOpsDuration.add(Date.now() - start);
}

function flagIfUnexpected(res) {
  // 4xx from the app's own validation/RBAC logic is expected traffic noise under load, not
  // a system failure. 5xx and connection-level failures (status 0) are the real signal.
  const isUnexpected = res.status === 0 || res.status >= 500;
  unexpectedErrors.add(isUnexpected);
}

// ---- weighted dispatch — the default export every entrypoint script hands to k6 ----
export function mixedTraffic(data) {
  const r = Math.random();
  if (r < 0.45) {
    browse(data);
  } else if (r < 0.75) {
    orderJourney(data);
  } else if (r < 0.9) {
    repeatLogin(data);
  } else {
    adminOps(data);
  }
  sleep(Math.random() * 0.5 + 0.2); // 200-700ms think time, keeps this from being an unrealistic tight loop
}

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '30s';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 10);
const MAX_VUS = Number(__ENV.MAX_VUS || 50);

export const options = {
    scenarios: {
        publish_events: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: PRE_ALLOCATED_VUS,
            maxVUs: MAX_VUS,
            gracefulStop: '30s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{operation:publish_event}': ['p(95)<500'],
        checks: ['rate==1'],
        dropped_iterations: ['count==0'],
    },
};

export function setup() {
    const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    const eventType = `load-test.${runId}`;

    const response = http.post(
        `${BASE_URL}/endpoints`,
        JSON.stringify({
            url: `${BASE_URL}/mock-receiver`,
            eventTypes: [eventType],
        }),
        {
            headers: {
                'Content-Type': 'application/json',
            },
            tags: {
                operation: 'create_endpoint',
            },
        }
    );

    if (response.status !== 201) {
        exec.test.abort(
            `Endpoint creation failed: ${response.status} ${response.body}`
        );
    }

    const endpointId = response.json('id');

    if (!endpointId) {
        exec.test.abort('Endpoint ID was not returned');
    }

    console.log(`Run ID: ${runId}`);
    console.log(`Event type: ${eventType}`);
    console.log(`Endpoint ID: ${endpointId}`);

    return { runId, eventType, endpointId };
}

export default function (data) {
    const iteration = exec.scenario.iterationInTest;
    const idempotencyKey = `${data.runId}-${iteration}`;

    const payload = JSON.stringify({
        type: data.eventType,
        payload: {
            source: 'k6-load-test',
            runId: data.runId,
            sequence: iteration,
        },
    });

    const response = http.post(
        `${BASE_URL}/events`,
        payload,
        {
            headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
            },
            tags: {
                operation: 'publish_event',
            },
        }
    );

    check(response, {
        'event accepted (202)': (r) => r.status === 202,
        'event ID returned': (r) => {
            try {
                return Boolean(r.json('eventId'));
            } catch (_) {
                return false;
            }
        },
    });
}

export function teardown(data) {
    console.log('=== Load generation completed ===');
    console.log(`Run ID: ${data.runId}`);
    console.log(`Event type: ${data.eventType}`);
    console.log(`Endpoint ID: ${data.endpointId}`);
    console.log('Verify delivery outcomes in PostgreSQL.');
}
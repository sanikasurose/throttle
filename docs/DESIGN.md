# Design Notes

## Why I built this
I wanted to understand how production APIs prevent abuse and ensure 
reliability. Rate limiting shows up everywhere — Stripe, GitHub, every 
public API — so I built one from scratch instead of using a library.

## Algorithm choice
I went with sliding window over fixed window. Fixed window has a known 
exploit: you can double your quota by firing requests at the end of one 
window and the start of the next. Sliding window anchors the calculation 
to the current timestamp on every request so that can't happen.

## Redis backing store
The initial design used an in-memory ConcurrentHashMap. That works for 
a single server instance but breaks under horizontal scaling — two 
instances would have separate maps and quotas wouldn't add up correctly. 
Redis gives all instances a shared source of truth.

## Atomicity with Lua
The evict → count → conditional-add sequence must be atomic. MULTI/EXEC 
and pipelining both queue commands blindly and can't branch on an 
intermediate result (the count), so neither works here. Instead, a Lua 
script executes all three operations as a single atomic unit on Redis. 
No other client can observe or modify the key between the three steps.

## Concurrency approach
The Java layer has no locks — all concurrency is handled by Redis. The 
Lua script is the critical section. Two requests for the same user hit 
Redis sequentially at the script level, which is correct behavior.

## Clock interface
I needed deterministic tests for time-dependent logic without 
Thread.sleep(). Injecting a Clock interface lets tests use a FakeClock 
that advances on command. Every time-dependent test in the suite uses 
this pattern — zero sleep calls anywhere.

## No Spring annotations in core
The core package (RateLimiter, RateLimitPolicy, RequestRecord, Clock) 
has zero Spring imports. All wiring happens in ThrottleConfig. This 
means the entire core can be tested with plain JUnit — no Spring context 
needed, no slow startup, no mocking of beans.

## Key TTL
EXPIRE is called inside the Lua script on every allowed request, setting 
the key TTL to windowSeconds. Redis automatically reclaims memory for 
inactive users with no background cleanup job needed.
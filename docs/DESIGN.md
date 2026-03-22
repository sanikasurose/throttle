# Design Notes

## Why I built this
I wanted to understand how production APIs prevent abuse. Rate limiting
shows up everywhere — Stripe, GitHub, every public API — so I built one
from scratch instead of using a library.

## Algorithm choice
I went with sliding window over fixed window. Fixed window has a known
exploit: you can double your quota by firing requests at the end of one
window and the start of the next. Sliding window anchors the calculation
to the current timestamp so that can't happen.

## Concurrency approach
I synchronize on individual RequestRecord objects rather than the whole
ConcurrentHashMap. This means two different users can be processed truly
concurrently — they only block each other if they share the same userId,
which is correct behavior.

## Why the Clock interface exists
I needed deterministic tests for time-dependent logic without Thread.sleep().
Injecting a Clock interface lets tests use a FakeClock that advances on
command. This is a pattern used in production codebases at Google and others.

## Why Redis
The in-memory ConcurrentHashMap works for one server instance. If you run
two instances behind a load balancer, they have separate maps and quotas
don't add up correctly. Redis gives both instances a shared source of truth.
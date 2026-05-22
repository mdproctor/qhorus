---
id: PP-20260522-71db6a
title: "JPA scan() LIKE prefix branches must escape metacharacters and declare ESCAPE '!'"
type: rule
scope: repo
applies_to: "JpaChannelStore.scan(), ReactiveJpaChannelStore.scan(), and any future JPA store scan() method that adds a prefix filter"
severity: important
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/store/jpa/JpaChannelStore.java
  - runtime/src/main/java/io/casehub/qhorus/runtime/store/jpa/ReactiveJpaChannelStore.java
violation_hint: "LIKE appended without ESCAPE clause — prefix containing _ matches any single character instead of a literal underscore, producing wrong results with no error"
created: 2026-05-22
---

SQL `LIKE` treats `_` (any single character) and `%` (any sequence) as wildcards even
inside bound parameters. The in-memory store path uses `String.startsWith()` which is
exact — the JPA path must match that semantic. When appending a LIKE prefix clause in
any JPA `scan()` method, pre-escape `!`, `%`, and `_` in the prefix value and include
`ESCAPE '!'` in the JPQL fragment: `" AND name LIKE ?" + idx++ + " ESCAPE '!'"`, with
the parameter value produced by `prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"`.
The existing `namePattern` branch has this same gap; apply the same fix when that branch is
next touched.

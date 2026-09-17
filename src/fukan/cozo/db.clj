(ns fukan.cozo.db
  "The Cozo engine seam — a thin wrapper over `cozo-clj`.

   `open` makes an in-memory Cozo db; `q` runs a CozoScript script and returns its
   `:rows` (a vector of row-vectors), throwing on a failed query so callers don't
   have to inspect the `:ok` flag. The JNI native lib auto-downloads on first
   `open` (to `~/.cozo_java_native_lib/`). This is the only namespace that knows
   `cozo-clj` exists; everything else speaks CozoScript strings + row vectors."
  (:require [cozo-clj.core :as cozo]))

(defn ^{:malli/schema [:=> [:cat] :CozoDb]}
  open
  "Open a fresh in-memory Cozo database."
  []
  (cozo/open-db))

(defn ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  close
  "Close a Cozo database, releasing its native resources."
  [db]
  (cozo/close-db db))

(defn ^{:malli/schema [:=> [:cat :CozoDb :string [:? :map]] [:vector :any]]}
  q
  "Run CozoScript `script` against `db` and return its `:rows` (a vector of
   row-vectors). With a `params` map, the entries are bound as `$name` variables
   in the script (data crosses as JSON — no string interpolation needed).
   `cozo-clj` throws an ex-info carrying the engine's error on a failed query."
  ([db script] (q db script nil))
  ([db script params]
   (:rows (cozo/query db script params))))

(def ^:private writes
  "Bumped by every write to an already-open db — see `write-generation`."
  (atom 0))

(defn ^{:malli/schema [:=> [:cat] :int]}
  write-generation
  "A counter that moves whenever an ALREADY-OPEN db is written to. A db handle is mutable and
   keeps its identity across writes, so anything memoized per handle over the db's CONTENTS
   (`query/buckets-of`) keys on the handle AND this — the handle alone says which db, not which
   state of it.

   One counter for every handle rather than one per handle: the only consumer holds a single
   entry, so a write to some other db costs it at most one recompute, and a per-handle table
   would have to hold the handles it is meant to let go of."
  []
  @writes)

(defn ^{:malli/schema [:=> [:cat] :nil]}
  wrote!
  "Announce a write to an already-open db (`write-generation`). The substrate's one write path
   (`mirror/insert-datoms`) calls it; a fresh load needs none, because it makes a fresh handle."
  []
  (swap! writes inc)
  nil)

(defn ^{:malli/schema [:=> [:cat [:=> [:catn [:db :CozoDb]] :any]] :any]}
  with-db
  "Open a db, call `(f db)`, and close the db (even on throw). Returns f's value."
  [f]
  (let [db (open)]
    (try (f db) (finally (close db)))))

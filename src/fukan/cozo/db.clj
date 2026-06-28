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

(defn ^{:malli/schema [:=> [:cat :CozoDb :string] [:vector :any]]}
  q
  "Run CozoScript `script` against `db` and return its `:rows` (a vector of
   row-vectors). With a `params` map, the entries are bound as `$name` variables
   in the script (data crosses as JSON — no string interpolation needed).
   `cozo-clj` throws an ex-info carrying the engine's error on a failed query."
  ([db script] (q db script nil))
  ([db script params]
   (:rows (cozo/query db script params))))

(defn ^{:malli/schema [:=> [:cat [:=> [:catn [:db :CozoDb]] :any]] :any]}
  with-db
  "Open a db, call `(f db)`, and close the db (even on throw). Returns f's value."
  [f]
  (let [db (open)]
    (try (f db) (finally (close db)))))

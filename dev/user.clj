(ns user
  "Fukan's own REPL entry point — and fukan's own use of the SHIPPED cockpit
   (`fukan.repl`). fukan-on-itself is the first consumer, not a privileged one: this
   namespace supplies fukan's defaults and nothing else, so the surface an external
   project consumes is the surface fukan itself drives on every REPL start.

   The kernel feedback loop: build the model with `(go)`, query it with `cq/q`, run laws
   with `(check)`. `(refresh)` reloads changed code + specs and rebuilds. (The HTTP server
   + web explorer are PAUSED — parked under .paused/.)"
  (:require [fukan.repl :as repl
             :refer [reset refresh status architecture grammar correspondence show focus
                     check drift undeclared-code-dependencies encapsulation leaves frontier
                     deps purity type-drift]]))

;; fukan's own defaults: it names itself, extracts its own `src`, and — unlike a consumer —
;; it edits its own source as well as its specs, so the reload set is wider than the spec dirs.
(def ^:private defaults
  {:src "src" :title "FUKAN" :reload-dirs ["src" "dev" "canvas"]})

(defn go
  "Build the held model headlessly. Options as `fukan.repl/go`; fukan's defaults are
   :src \"src\", :title \"FUKAN\" and :reload-dirs [\"src\" \"dev\" \"canvas\"]."
  ([] (go {}))
  ([opts] (repl/go (merge defaults opts))))

(comment
  (go)
  (go {:src nil})     ; design-only
  (refresh)
  (grammar "fukan.common.vocab.grouping")
  (show 'kernel)
  (check)
  (drift)
  (leaves)
  (frontier)
  (status))

(ns fukan.model.extraction
  "The extraction plug-point: a slot for a project's custom code extractor.

   fukan ships only this mechanic — a place to register ONE extractor (a fn
   `code-root -> structure-db`) and a runner. A project supplies its own custom
   extractor for its own code (fukan-on-itself's is the Clojure extractor over
   fukan's `src/`). build-model consults this registry rather than naming any
   specific extractor, so the pipeline stays generic.

   Splitting a custom extractor into reusable pieces later — a programming-language
   layer, architecture-sets — is deferred; there's no second target to justify it
   yet."
  (:refer-clojure :exclude []))

(defonce ^:private extractor (atom nil))
(defonce ^:private fact-extractor (atom nil))

(defn register-extractor!
  "Register the project's code extractor: a fn `code-root -> structure-db`.
   Replaces any previously registered extractor (a project has one)."
  [f]
  (reset! extractor f)
  nil)

(defn run-extractor
  "Run the registered extractor over `code-root`, returning its structure-db — or
   nil when no extractor is registered (a design-only build)."
  [code-root]
  (when-let [f @extractor]
    (f code-root)))

(defn register-fact-extractor!
  "Register the project's FACT extractor: a fn `code-root -> {:roots :var-usages}` (the
   engine-agnostic extraction facts the native Cozo build consumes — no datascript). A
   project registers one; this replaces any previous."
  [f]
  (reset! fact-extractor f)
  nil)

(defn extract-facts
  "Run the registered fact extractor over `code-root`, returning its `{:roots :var-usages}`
   facts — or `{:roots [] :var-usages []}` when none is registered (a design-only build)."
  [code-root]
  (if-let [f @fact-extractor]
    (f code-root)
    {:roots [] :var-usages []}))

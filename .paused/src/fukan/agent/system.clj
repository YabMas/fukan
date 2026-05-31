(ns fukan.agent.system
  "Operating Fukan: status, refresh, help, source. Flat namespace.
   Sandbox surface alongside fukan.agent.api."
  (:require [fukan.infra.model :as infra-model]
            [fukan.agent.views-loader :as views-loader]
            [clojure.repl :as repl]
            [clojure.string :as str]))

(defn ^{:agent/doc "Snapshot of the daemon and loaded Model."
        :agent/example "(status)"}
  status
  []
  (let [m (infra-model/get-model)]
    {:model-loaded?   (some? m)
     :target          (infra-model/get-src)
     :primitive-count (if m (count (:primitives m)) 0)
     :relation-count  (if m (count (:edges m)) 0)
     :artifact-count  (if m (count (:artifacts m)) 0)
     :violation-count (if m (count (or (:violations m) [])) 0)
     :views           (views-loader/last-report)}))

(defn ^{:agent/doc "Rebuild the loaded Model. Blocks; returns the new status."
        :agent/example "(refresh)"}
  refresh
  []
  (infra-model/refresh-model)
  (views-loader/auto-load! (infra-model/get-src))
  (status))

(defn- canvas-ns-symbols
  "Return all currently-loaded namespace symbols under the `canvas.*` root."
  []
  (->> (all-ns)
       (map ns-name)
       (filter (fn [sym] (str/starts-with? (str sym) "canvas.")))))

(def ^:private dynamic-load-loaders
  "Namespaces whose vars change in ways the substrate-aware reset path must
   pick up. Two patterns covered:

   1. Loader namespaces whose `:require` list is the registration surface
      for a dynamic-load registry (multimethod or var-collection).
      Reloading the loader re-evaluates its `:require` form, which causes
      Clojure's standard require machinery to load any newly-added file
      in the registry directory. Add new loaders here as the canvas
      substrate grows.

   2. Agent sandbox surface namespaces — `fukan.agent.api` is itself
      bound into the SCI context at first eval. New `defn`s added to it
      don't appear in `bin/fukan eval` until the namespace reloads AND
      the SCI context rebuilds. The reset path does both."
  '[fukan.canvas.project.clojure
    fukan.canvas.lens.registry
    fukan.canvas.instruct.registry
    fukan.agent.api])

(defn ^{:agent/doc "Reload all canvas namespaces (incl. newly-added files) + rebuild
                    the Model. Use this after adding a new canvas/<...>.clj file —
                    canvas files are auto-discovered, no registry edit required.
                    Also reloads the dynamic-load registry loaders under
                    `fukan.canvas.{project,lens,instruct}` and the agent
                    sandbox surface `fukan.agent.api`, so newly-added
                    projection/lens/scenario files in src/ AND newly-added
                    agent-api fns are picked up. Rebuilds the SCI context
                    afterward so the new agent-api vars appear in subsequent
                    `bin/fukan eval` calls.

                    Blocks; returns the new status. Heavier than `refresh` —
                    equivalent to `clj -M:run` restart minus the server bounce."
        :agent/example "(reset)"}
  reset
  []
  ;; Strategy: reload only the canvas surface — the canvas.* port namespaces
  ;; AND the canvas-source projection — never library deps (datascript and
  ;; friends register multimethods that break on reload).
  ;;
  ;; Step 1: reload each already-loaded canvas.* namespace so source edits
  ;; inside existing canvas files are picked up. If a previously-loaded
  ;; canvas namespace's file has been deleted, remove the dangling ns and
  ;; carry on — the next build will simply not re-discover it.
  (doseq [ns-sym (canvas-ns-symbols)]
    (try
      (require ns-sym :reload)
      (catch java.io.FileNotFoundException _
        (remove-ns ns-sym))))
  ;; Step 2: reload the dynamic-load loaders + agent sandbox surface. For
  ;; canvas/{project,lens,instruct} loaders: reloading re-runs the
  ;; `:require` form, picking up newly-added projection/lens/scenario
  ;; files. For `fukan.agent.api`: reloading picks up newly-added
  ;; agent-api fns (new vars in ns-publics) so the SCI context rebuild
  ;; in step 3 sees them.
  (doseq [loader-sym dynamic-load-loaders]
    (try
      (require loader-sym :reload)
      (catch java.io.FileNotFoundException _
        ;; loader removed/renamed since last load — drop the dangling ns
        (when (find-ns loader-sym) (remove-ns loader-sym)))))
  ;; Step 3: rebuild the SCI context so the new agent-api vars (from step 2's
  ;; `fukan.agent.api` reload) appear in subsequent eval calls. The SCI
  ;; context is built once at first eval from `(ns-publics 'fukan.agent.api)`
  ;; + `(ns-publics 'fukan.agent.system)`; without this reset the new vars
  ;; stay invisible to `bin/fukan eval` until daemon restart.
  ;;
  ;; Resolved at runtime via `requiring-resolve` to break the static cycle —
  ;; `fukan.agent.sci` already requires `fukan.agent.system` to merge its
  ;; ns-publics into the SCI bindings, so a static require here would loop.
  (when-let [reset-fn (requiring-resolve 'fukan.agent.sci/reset-ctx!)]
    (reset-fn))
  ;; Step 4: rebuild the model. canvas-source/build-canvas-db walks
  ;; canvas/**/*.clj at each build, so newly-added files are picked up
  ;; here without any additional reload step.
  (infra-model/refresh-model)
  (views-loader/auto-load! (infra-model/get-src))
  (status))

(def ^:private surface-namespaces ['fukan.agent.api 'fukan.agent.system])

(defn- collect-var-meta [ns-sym]
  (require ns-sym)
  (->> (ns-publics ns-sym)
       vals
       (map (fn [v]
              (let [m (meta v)]
                {:name      (:name m)
                 :layer     (:agent/layer m)
                 :doc       (or (:agent/doc m) (:doc m) "")
                 :example   (or (:agent/example m) "")
                 :origin    (:agent/origin m)
                 :var       v})))))

(defn ^{:agent/doc "List the surface. Without args: nested map grouped by namespace
                    and (for fukan.agent.api) by layer. With a symbol: docstring +
                    signatures + example for that single fn."
        :agent/example "(help) (help 'primitives)"}
  help
  ([]
   (let [api-meta (collect-var-meta 'fukan.agent.api)
         sys-meta (collect-var-meta 'fukan.agent.system)]
     {'fukan.agent.api
      (reduce (fn [acc {:keys [layer] :as m}]
                (let [k (or layer :other)]
                  (update acc k (fnil conj []) (dissoc m :var))))
              {:L0 [] :L1 [] :L2 [] :trust [] :weigh []}
              api-meta)
      'fukan.agent.system
      (mapv #(dissoc % :var) sys-meta)}))
  ([fn-sym]
   (some (fn [ns-sym]
           (require ns-sym)
           (when-let [v (ns-resolve (find-ns ns-sym) fn-sym)]
             (let [m (meta v)]
               {:name      (:name m)
                :ns        ns-sym
                :layer     (:agent/layer m)
                :doc       (or (:agent/doc m) (:doc m) "")
                :example   (or (:agent/example m) "")
                :arglists  (str (:arglists m))})))
         surface-namespaces)))

(defn- normalize-source
  "Strip top-level metadata map from a defn source string so that
   `(defn ^{...}\\n  name` becomes `(defn name` — more useful as a template."
  [src]
  (str/replace src #"\(defn \^[\s\S]*?\}\s*\n\s+" "(defn "))

(defn ^{:agent/doc "Return the source text of an L1/L2 fn so the agent can read
                    built-in views as templates."
        :agent/example "(source 'drift)"}
  source
  [fn-sym]
  (some (fn [ns-sym]
          (require ns-sym)
          (when-let [_v (ns-resolve (find-ns ns-sym) fn-sym)]
            (let [fqsym (symbol (str ns-sym) (str fn-sym))
                  src   (try (clojure.repl/source-fn fqsym)
                             (catch Exception _ nil))]
              {:name fn-sym
               :ns   ns-sym
               :source (if src (normalize-source src) "<source unavailable>")})))
        surface-namespaces))

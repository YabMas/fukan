(ns fukan.repl-test
  "The cockpit's contract — the surface an external consumer actually uses.

   The reload test is the regression for a live bug: clj-reload was initialised with
   :dirs [\"src\" \"dev\"], so canvas namespaces were never tracked, and a spec edit was
   silently ignored — (refresh) reloaded 21 fukan.* namespaces and zero canvas.*."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.projection.architecture :as arch]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.infra.model :as infra-model]
            [fukan.repl :as repl]))

(deftest reload-dirs-default-to-spec-dirs
  (testing "a consumer supplying neither gets their spec dir reloaded"
    (repl/go {:src nil})
    (is (= ["canvas"] (:spec-dirs (repl/config))))
    (is (= ["canvas"] (:reload-dirs (repl/config)))
        "reload-dirs defaults to spec-dirs — the consumer edits specs, not fukan's src")))

(deftest reload-dirs-are-explicitly-overridable
  (testing "fukan itself also edits its own source, so it widens the set"
    (repl/go {:src nil :reload-dirs ["src" "dev" "canvas"]})
    (is (= ["src" "dev" "canvas"] (:reload-dirs (repl/config))))))

(deftest cockpit-tracks-spec-namespaces-for-reload
  (testing "clj-reload TRACKS the canvas namespaces — else a spec edit is silently ignored"
    (repl/go {:src nil :reload-dirs ["canvas"]})
    (let [tracked (map str (keys (:namespaces @@(resolve 'clj-reload.core/*state))))]
      (is (seq (filter #(str/starts-with? % "canvas.") tracked))
          "canvas.* namespaces must be tracked by clj-reload"))))

(deftest go-with-nil-src-builds-a-design-only-model
  (testing "model-first: a consumer authors specs before any code exists"
    (let [m (repl/go {:src nil})]
      (is (some? m)))))

(deftest go-with-absent-code-root-builds-a-design-only-model
  (testing "the core's path need not exist yet — every authored Operation is drift"
    (let [m (repl/go {:src "no/such/path/at/all"})]
      (is (some? m) "an absent code-root yields a design-only build, not an exception"))))

(deftest changed-reload-survives-a-touched-canvas-spec
  (testing "regression for 66587ea1: `{:only :loaded}` reloads every tracked namespace in
            one pass and can mis-order + throw once canvas is tracked; `:only :changed`
            (the default) reloads only the touched canvas namespaces and the model comes
            back intact. Drives a REAL reload — touches every canvas/**/*.clj mtime (never
            content, so `jj st` stays clean), calls `refresh`, and rebuilds."
    (repl/go {:src nil :reload-dirs ["dev"]})                  ; force a state clj-reload must re-init from
    (repl/go {:src "src" :reload-dirs ["src" "dev" "canvas"]}) ; real re-init — fresh mtime baseline
    (let [future-mtime (+ (System/currentTimeMillis) 60000)]
      (doseq [f (file-seq (io/file "canvas"))
              :when (str/ends-with? (str f) ".clj")]
        (.setLastModified f future-mtime)))
    (repl/refresh)
    (is (empty? (law/check (infra-model/get-model)))
        "0 violations after a changed-only reload of every touched canvas namespace — with
         `{:only :loaded}` this either throws (namespace mis-ordering) or comes back with
         the silent-corruption signature")))

(deftest architecture-banner-is-not-hardcoded-to-fukan
  (testing "a consumer's model must not print FUKAN as its title"
    (repl/go {:src nil})
    (let [m   (infra-model/get-model)
          out (arch/architecture-overview m "BRIAN")]
      (is (str/includes? out "BRIAN"))
      (is (not (str/includes? out "FUKAN"))))))

(deftest architecture-banners-the-title-held-by-go
  (testing "the title reaches the bare (architecture) command through go's config — the cockpit's
            principle: `go` holds the config, every other command stays bare"
    (repl/go {:src nil :title "BRIAN"})
    (let [out (with-out-str (repl/architecture))]
      (is (str/includes? out "BRIAN — projected architecture overview")
          "the held :title must name the system in the banner")
      (is (not (str/includes? out "FUKAN"))
          "a consumer's model must not print fukan's name")))
  (testing "and a consumer holding no title gets an unnamed banner, not a stale one"
    (repl/go {:src nil :title "BRIAN"})
    (repl/go {:src nil})
    (let [out (with-out-str (repl/architecture))]
      (is (str/starts-with? out "projected architecture overview")
          "go replaces the held config wholesale — no title survives the rebuild"))))

(deftest architecture-degrades-on-a-model-with-no-subsystems
  (testing "a fresh consumer authors specs before clustering them — say so, don't print an empty frame"
    (let [empty-db (build/vars->cozo [])
          out      (arch/architecture-overview empty-db "BRIAN")]
      (is (str/includes? out "No subsystems")
          "an unclustered model must say so rather than render a bare header"))))

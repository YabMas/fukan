(ns fukan.canvas.project.clojure.function-to-defn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.canvas.core.shape :as shape]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/infra/server.clj :: start_server.
(def ^:private start-server-element
  {:model-element-kind :Affordance
   :canvas-role        :canvas/function
   :stable-id          "infra.server/function/start_server"
   :entity-name        "start_server"
   :module-coord       "infra.server"
   :doc                "Start the HTTP server on the given options."
   :inputs             [['opts (shape/parse :ServerOpts)]]
   :outputs            (shape/parse '(optional :ServerInfo))})

(deftest produces-valid-projection
  (let [p (core/project :clojure start-server-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure start-server-element {:registry registry})]
    (is (= :clojure/function-to-defn (:projection-kind p)))
    (is (= :Affordance               (:model-element-kind p)))))

(deftest target-derivation-kebab-cases
  (let [p (core/project :clojure start-server-element {:registry registry})]
    (is (= "src/fukan/infra/server.clj" (-> p :target :path)))
    (is (= "fukan.infra.server"         (-> p :target :namespace)))
    (is (= "start-server"               (-> p :target :symbol)))))

(deftest template-defn-shape
  (let [p (core/project :clojure start-server-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defn start-server"))
    (is (str/includes? t "\"Start the HTTP server on the given options.\""))
    (is (str/includes? t "[opts]"))
    (is (str/includes? t ":malli/schema"))))

(deftest malli-schema-cat-and-return
  (let [p (core/project :clojure start-server-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "[:=> [:cat :ServerOpts] [:maybe :ServerInfo]]"))))

(deftest exception-stub-carries-canvas-id
  (let [p (core/project :clojure start-server-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(throw (ex-info \"start-server: not yet implemented\""))
    (is (str/includes? t ":canvas-id \"infra.server/function/start_server\""))))

(deftest zero-arg-function
  (testing "stop_server :: takes [] gives :Unit"
    (let [el {:model-element-kind :Affordance
              :canvas-role        :canvas/function
              :stable-id          "infra.server/function/stop_server"
              :entity-name        "stop_server"
              :module-coord       "infra.server"
              :doc                "Stop the running HTTP server."
              :inputs             []
              :outputs            (shape/parse :Unit)}
          p (core/project :clojure el {:registry registry})
          t (:template p)]
      (is (str/includes? t "(defn stop-server"))
      (is (str/includes? t "[:=> [:cat] :nil]"))
      (is (str/includes? t "[]")))))

(deftest multiple-parameters
  (let [el {:model-element-kind :Affordance
            :canvas-role        :canvas/function
            :stable-id          "x/function/combine"
            :entity-name        "combine"
            :module-coord       "x"
            :doc                "Combine."
            :inputs             [['a (shape/parse :Integer)]
                                 ['b (shape/parse :String)]]
            :outputs            (shape/parse :Boolean)}
        p (core/project :clojure el {:registry registry})
        t (:template p)]
    (is (str/includes? t "[a b]"))
    (is (str/includes? t "[:=> [:cat :int :string] :boolean]"))))

(deftest effects-and-emits-surface-in-context
  (let [el (assoc start-server-element
                  :effects [:log-write]
                  :emits   [:ServerStarted])
        p  (core/project :clojure el {:registry registry})]
    (is (= [:log-write]     (-> p :context :effects)))
    (is (= [:ServerStarted] (-> p :context :emits)))
    (is (= 1                (-> p :context :arity)))))

(deftest module-segment-underscores-kebab-in-ns
  (let [el {:model-element-kind :Affordance
            :canvas-role        :canvas/function
            :stable-id          "project_layer.registry/function/make_registry"
            :entity-name        "make_registry"
            :module-coord       "project_layer.registry"
            :doc                "Make a registry."
            :inputs             []
            :outputs            (shape/parse :Any)}
        p (core/project :clojure el {:registry registry})]
    (is (= "fukan.project-layer.registry" (-> p :target :namespace)))
    (is (= "make-registry"                (-> p :target :symbol)))))

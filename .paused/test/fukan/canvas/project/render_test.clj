(ns fukan.canvas.project.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.render :as render]))

(def ^:private sample
  {:projection-kind    :clojure/type-to-malli
   :lens-id            :clojure
   :model-element-kind :Type
   :model-element-id   "infra.server/type/ServerOpts"
   :target             {:path      "src/fukan/infra/server.clj"
                        :namespace "fukan.infra.server"
                        :symbol    "ServerOpts"}
   :template           "(def ServerOpts [:map [:port :int]])"
   :prose              "HTTP server configuration."
   :context            {:canvas-source-ref "canvas/infra/server.clj"}})

(deftest renders-title-from-projection-kind
  (let [md (render/to-markdown sample)]
    (is (str/starts-with? md "# clojure/type-to-malli"))))

(deftest renders-target-line
  (let [md (render/to-markdown sample)]
    (is (str/includes? md "**Target:**"))
    (is (str/includes? md "src/fukan/infra/server.clj"))
    (is (str/includes? md "fukan.infra.server/ServerOpts"))))

(deftest includes-prose-and-fenced-template
  (let [md (render/to-markdown sample)]
    (is (str/includes? md "HTTP server configuration."))
    (is (str/includes? md "```clojure\n(def ServerOpts [:map [:port :int]])\n```"))))

(deftest source-ref-renders-when-present
  (let [md (render/to-markdown sample)]
    (is (str/includes? md "canvas/infra/server.clj"))))

(deftest omits-code-block-when-template-nil
  (testing "prose-only projection produces a markdown body with no fence"
    (let [md (render/to-markdown (assoc sample :template nil))]
      (is (not (str/includes? md "```"))))))

(deftest renders-element-line
  (let [md (render/to-markdown sample)]
    (is (str/includes? md "**Element:** infra.server/type/ServerOpts (Type)"))))

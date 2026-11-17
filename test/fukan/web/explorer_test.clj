(ns fukan.web.explorer-test
  "The EXPLORER's boundary — what a request must look like before a projection sees it, and that
   every way a request can go wrong is an ANSWER rather than a failed request.

   What is drawn is the view projection's and is tested there. These pin the parts that are this
   namespace's own: parsing, the four alternatives of an answer, and that the model is read
   through the function it was handed at the moment of each request."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.cozo.build :as build]
            ;; side-effect: registers the malli dialect + Clojure extractor + check engine
            [fukan.infra.model]
            [fukan.web.explorer :as explorer]
            [org.httpkit.server :as http]))

(defstructure XShelf
  "A shelf holding other shelves — one relation to nest by, one to draw."
  {:xholds [:* XShelf]
   :xfaces [:* XShelf]})

(XShelf low)
(XShelf mid  {:xholds [low]})
(XShelf high {:xholds [mid] :xfaces [low]})

(defn- db [] (build/with-grammar (build/vars->cozo [#'low #'mid #'high]) nil))

(def ^:private high-id (str (ns-name *ns*) "/high"))

(deftest a-well-formed-request-parses-to-a-spec-and-open-paths
  (is (= [:view {:nest ["xholds"] :link ["xfaces"]} #{[high-id]}]
         (explorer/parse-request (db) {"nest" "xholds"
                                       "link" " xfaces ,"
                                       "open" (json/generate-string [[high-id]])})))
  (let [spec (fn [params] (second (explorer/parse-request (db) (assoc params "nest" "xholds"))))]
    (is (= high-id (:root (spec {"root" high-id}))))
    (is (true? (:unplaced (spec {"unplaced" "1"}))))
    (is (= '[(XShelf ?n)] (:select (spec {"select" "[(XShelf ?n)]"}))))))

(deftest a-malformed-request-is-rejected-with-what-was-wrong
  (let [rejected (fn [params]
                   (let [[tag reason] (explorer/parse-request (db) params)]
                     (when (= :rejected tag) reason)))]
    (is (re-find #"nest" (rejected {})))
    (is (re-find #"xhold\b" (rejected {"nest" "xhold"}))
        "a relation the model does not declare is named back")
    (is (re-find #"open" (rejected {"nest" "xholds" "open" "{\"not\":\"paths\"}"})))
    (is (re-find #"open" (rejected {"nest" "xholds" "open" "[[1,2]]"})))
    (is (re-find #"select" (rejected {"nest" "xholds" "select" "(XShelf ?n"})))
    (is (re-find #"select" (rejected {"nest" "xholds" "select" "42"})))))

(deftest every-outcome-is-exactly-one-answer
  (testing "a graph"
    (let [{:keys [at answer]} (explorer/answer db {"nest" "xholds" "link" "xfaces"})
          [tag graph]         answer]
      (is (string? at))
      (is (= :graph tag))
      (is (= ["high"] (map :label (:nodes graph))))))
  (testing "a rejection"
    (is (= :rejected (first (:answer (explorer/answer db {"nest" "nope"}))))))
  (testing "a selection the engine refuses"
    (let [[tag reason] (:answer (explorer/answer db {"nest" "xholds" "select" "[(no-such-rule ?n)]"}))]
      (is (= :unanswerable tag))
      (is (string? reason))))
  (testing "no model"
    (is (= [:no-model] (:answer (explorer/answer (constantly nil) {"nest" "xholds"}))))))

(deftest a-declaration-is-answered-for-a-declared-node-only
  (let [[tag d] (:answer (explorer/declaration db {"id" high-id}))]
    (is (= :declaration tag))
    (is (re-find #"\(XShelf high" (:text d)))
    (is (re-find #"XShelf$" (:sort d))))
  (is (= [:missing] (:answer (explorer/declaration db {"id" "no.such/node"}))))
  (is (= :rejected (first (:answer (explorer/declaration db {})))))
  (is (= [:no-model] (:answer (explorer/declaration (constantly nil) {"id" high-id})))))

(deftest the-relations-on-offer-are-answered-or-no-model
  (let [[tag offered] (:answer (explorer/choices db))]
    (is (= :choices tag))
    (is (some #(= "xfaces" (:name %)) (:relations offered))))
  (is (= [:no-model] (:answer (explorer/choices (constantly nil))))))

(deftest the-model-is-read-at-each-request
  (let [held   (atom nil)
        server (explorer/start 0 #(deref held))
        get    (fn [path]
                 (json/parse-string
                  (slurp (str "http://localhost:" (http/server-port server) path))
                  true))
        enc    #(java.net.URLEncoder/encode % "UTF-8")]
    (try
      (is (= ["no-model"] (:answer (get "/api/graph?nest=xholds"))))
      (reset! held (db))
      (is (= ["high"] (map :label (:nodes (second (:answer (get "/api/graph?nest=xholds"))))))
          "the model that appeared after the server started is the one the next request reads")
      (is (= ["high" "mid"]
             (map :label (:nodes (second (:answer (get (str "/api/graph?nest=xholds&open="
                                                            (enc (json/generate-string [[high-id]]))))))))))
      (is (= "declaration" (first (:answer (get (str "/api/node?id=" (enc high-id)))))))
      (is (some #(= "xholds" (:name %)) (:relations (second (:answer (get "/api/views"))))))
      (finally (explorer/stop server)))))

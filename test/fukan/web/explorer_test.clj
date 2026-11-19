(ns fukan.web.explorer-test
  "The EXPLORER's boundary — what a request must look like before a projection sees it, and that
   every way a request can go wrong is an ANSWER rather than a failed request.

   What is drawn is the view projection's and is tested there. These pin the parts that are this
   namespace's own: parsing, the four alternatives of an answer, and that the model is read
   through the function it was handed at the moment of each request."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
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
  (is (= [:view {:nest ["xholds"] :link ["xfaces"]} #{[high-id]} "light"]
         (explorer/parse-request (db) {"nest" "xholds"
                                       "link" " xfaces ,"
                                       "open" (json/generate-string [[high-id]])})))
  (let [spec (fn [params] (second (explorer/parse-request (db) (assoc params "nest" "xholds"))))]
    (is (= high-id (:root (spec {"root" high-id}))))
    (is (true? (:unplaced (spec {"unplaced" "1"}))))
    (is (= '[(XShelf ?n)] (:select (spec {"select" "[(XShelf ?n)]"}))))))

(deftest a-palette-is-parsed-with-the-request
  (let [parsed (fn [params] (explorer/parse-request (db) (assoc params "nest" "xholds")))]
    (is (= "light" (peek (parsed {}))) "light when the request names none")
    (is (= "dark" (peek (parsed {"theme" "dark"}))))
    (is (= :rejected (first (parsed {"theme" "sepia"}))))
    (is (re-find #"theme.*sepia" (second (parsed {"theme" "sepia"}))))))

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
    (is (re-find #"select" (rejected {"nest" "xholds" "select" "42"})))
    (testing "a parameter PRESENT with no value is refused, not read as absent"
      (doseq [p ["select" "open" "root" "unplaced" "theme" "link"]]
        (is (re-find (re-pattern p) (str (rejected {"nest" "xholds" p ""}))) p))
      (is (re-find #"unplaced" (str (rejected {"nest" "xholds" "unplaced" "yes"})))))))

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

(deftest a-drawing-request-is-a-graph-request-answered-as-a-picture
  (testing "the drawing of the graph the same parameters are answered with"
    (let [params        {"nest" "xholds" "link" "xfaces" "open" (json/generate-string [[high-id]])}
          [_ graph]     (:answer (explorer/answer db params))
          {:keys [at answer]} (explorer/drawing db params)
          [tag shown]   answer]
      (is (string? at))
      (is (= :drawing tag))
      (is (= (count (:nodes graph)) (:nodes shown)))
      (is (= (:unplaced graph) (:unplaced shown)))
      (is (re-find #"^<svg " (:svg shown)))
      (doseq [{:keys [label]} (:nodes graph)]
        (is (re-find (re-pattern (str ">" label "<")) (:svg shown))))))
  (testing "in the palette the request names"
    (let [svg (fn [theme] (:svg (second (:answer (explorer/drawing db {"nest" "xholds" "theme" theme})))))]
      (is (not= (svg "light") (svg "dark")))))
  (testing "refused exactly as the graph request is"
    (doseq [params [{"nest" "nope"}
                    {"nest" "xholds" "theme" "sepia"}
                    {"nest" "xholds" "select" "[(no-such-rule ?n)]"}]]
      (is (= (:answer (explorer/answer db params)) (:answer (explorer/drawing db params)))))
    (is (= [:no-model] (:answer (explorer/drawing (constantly nil) {"nest" "xholds"}))))))

(deftest an-address-reads-back-as-the-parameters-it-was-written-from
  (let [server (explorer/start 0 db)
        seen   (fn [params]
                 ;; what the transport hands a reading: a rejection names the parameter it read
                 (json/parse-string
                  (slurp (str "http://localhost:" (http/server-port server) "/api/graph?" (explorer/address params)))
                  true))]
    (try
      (testing "every encoding a view parameter takes survives the trip"
        (let [params {"nest"   "xholds"
                      "link"   "xfaces"
                      "select" "[(or-join [?n] (XShelf ?n) [?n :entity/name \"a b&c=d+e\"])]"
                      "open"   (json/generate-string [[high-id]])
                      "root"   high-id
                      "unplaced" "1"}
              [tag graph] (:answer (seen params))]
          (is (= "graph" tag))
          (is (= (second (:answer (explorer/answer db params)))
                 (json/parse-string (json/generate-string graph) true)
                 graph))))
      (testing "a value is read back as it was written, not as something like it"
        (is (re-find #"a b&c=d\+e%" (second (:answer (seen {"nest" "a b&c=d+e%"}))))))
      (testing "one request has one address"
        (is (= (explorer/address (array-map "nest" "a" "link" "b"))
               (explorer/address (array-map "link" "b" "nest" "a")))))
      (finally (explorer/stop server)))))

(deftest a-query-string-that-is-not-percent-encoding-reaches-no-reading
  (let [server (explorer/start 0 db)]
    (try
      (let [conn (.openConnection (java.net.URL. (str "http://localhost:" (http/server-port server)
                                                      "/api/graph?nest=%zz")))]
        (is (= 400 (.getResponseCode ^java.net.HttpURLConnection conn))))
      (finally (explorer/stop server)))))

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
      (is (= "drawing" (first (:answer (get "/api/drawing?nest=xholds")))))
      (is (some #(= "xholds" (:name %)) (:relations (second (:answer (get "/api/views"))))))
      (finally (explorer/stop server)))))

(deftest the-page-draws-nothing-and-names-no-vocabulary
  (let [page   (slurp (io/resource "public/explorer.html"))
        script (slurp (io/resource "public/explorer.js"))]
    (testing "the only script is the page's own: no layout library is loaded"
      (is (= ["explorer.js"] (map second (re-seq #"<script[^>]*src=\"([^\"]+)\"" page)))))
    (testing "the script creates no drawing element and holds no paint for one"
      (is (not (re-find #"createElementNS|<rect|<path|<polyline" script)))
      (is (not (re-find #"(?i)\belk\b|hsl\(" script)))
      (is (not (re-find #"g\.box[^{]*\{[^}]*(fill|stroke)" page))))
    (testing "it asks for the drawing, with the address's own view parameters"
      (is (re-find #"api/drawing" script))
      (is (re-find #"\['nest', 'link', 'select', 'open', 'unplaced', 'root'\]" script)))
    (testing "the root is read from the address as one id, and an array root is replaced first"
      (is (re-find #"root: q\.get\('root'\) \|\| ''" script))
      ;; Every reading of the address — at load, on Back/Forward, for each request — is
      ;; `address()`, so the rewrite lives there and no read can see an array root.
      (let [at   (.indexOf script "const address = () =>")
            body (when (<= 0 at) (subs script at (.indexOf script "\n};" at)))]
        (is (some? body))
        (is (re-find #"root\.startsWith\('\['\)" (str body)))
        (is (re-find #"history\.replaceState" (str body))))
      (is (= 1 (count (re-seq #"new URLSearchParams\(location\.search\)" script)))))
    (testing "no sort and no relation of fukan's vocabulary is written in either"
      (is (not (re-find #"Module|Subsystem|Operation|may-depend|\(Band" (str page script)))))))

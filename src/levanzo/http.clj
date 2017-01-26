(ns levanzo.http
  (:require [levanzo.payload :as payload]
            [levanzo.routing :as routing]
            [levanzo.schema :as schema]
            [levanzo.hydra :as hydra]
            [levanzo.indexing :as indexing]
            [levanzo.namespaces :as lns]
            [clojure.string :as string]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [cemerick.url :as url])
  (:import [java.io StringWriter PrintWriter]))

;; Should we return information about the stack trace of an error?
(def *debug-errors* (atom false))
(defn set-debug-errors! [enabled] (swap! *debug-errors* (fn [_] enabled)))

(def *compact-responses* (atom true))
(defn set-compact-responses! [enabled] (swap! *compact-responses* (fn [_] enabled)))

(def *validate-responses* (atom true))
(defn set-validate-responses! [enabled] (swap! *validate-responses* (fn [_] enabled)))

(def *validate-requests* (atom true))
(defn set-validate-requests! [enabled] (swap! *validate-requests* (fn [_] enabled)))

(defn find-validation [validation-type validations-map method model]
  (log/debug "Finding validations in model " (-> model :common-props ::hydra/id))
  (->> model
       :operations
       (map #(if (and (= (-> method name string/upper-case) (-> % :operation-props ::hydra/method))
                      (some? (-> % :operation-props validation-type)))
               (let [validation-class-id (-> % :operation-props validation-type)]
                 (get validations-map validation-class-id))
               nil))
       first))
(defn find-expects-validation [validations-map method model]
  (find-validation ::hydra/expects validations-map method model))

(defn find-returns-validation [validations-map method model]
  (find-validation ::hydra/returns validations-map method model))

(defn ->404 [message]
  {:status 404
   :body   (json/generate-string {"@context" (lns/hydra "")
                                  "title" "404 Not Found"
                                  "description" message})})

(defn ->405 [message]
  {:status 405
   :body   (json/generate-string {"@context" (lns/hydra "")
                                  "title" "405 Method Not Allowed"
                                  "description" message})})

(defn ->422
  ([message errors]
   (if @*debug-errors*
     {:status 422
      :body (json/generate-string {"@context" (lns/hydra "")
                                   "title" "422 Unprocessable Entity"
                                   "description" (str errors)})}
     {:status 422
      :body (json/generate-string {"@context" (lns/hydra "")
                                   "title" "422 Unprocessable Entity"
                                   "description" message})}))
  ([message] (->422 message nil)))
(defn exception->string [ex]
  (let [sw (StringWriter.)
        pw (PrintWriter. sw)]
    (.printStackTrace ex pw)
    (.toString sw)))

(defn ->500 [ex]
  (if @*debug-errors*
    {:status 500
     :body   (json/generate-string {"@context" (lns/hydra "")
                                    "title" (str "500 " (.getMessage ex))
                                    "description" (exception->string ex)})}
    {:status 500
     :body   (json/generate-string {"@context" (lns/hydra "")
                                    "title" "500 Internal Server Error"})}))

(defn request->jsonld [{:keys [body]}]
  (if (some? body)
    (let [json (if (map? body)
                 body
                 (json/parse-string (slurp body)))]
      (payload/expand json))
    nil))

(defn response->jsonld [{:keys [body headers] :as response-map} {:keys [documentation-path base-url]}]
  (if (some? body)
    (let [body (if @*compact-responses*
                 (payload/compact body)
                 body)
          body (json/generate-string body)
          headers (merge headers
                         {"Content-Type" "application/ld+json"
                          "Link" (str "<" base-url documentation-path ">; rel=\"http://www.w3.org/ns/hydra/core#apiDocumentation\"")})
          headers (if (some? (get body "@id")) (assoc headers "Location" (get body "@id")) headers)]
      (-> response-map
          (assoc :body body)
          (assoc :headers headers)))
    response-map))

(defn validate-response [{:keys [body] :as response} validations-map mode method model]
  (if (and @*validate-responses* (and (not (nil? mode))
                                      (not (nil? model))))
    (let [_ (log/debug "Validating response for method " method)
          predicate (find-returns-validation validations-map method model)
          _ (log/debug "Found validation predicates " (some? predicate))
          errors (if (some? predicate) (predicate mode validations-map body) [])]
      (if (empty? errors)
        (do
          (log/debug "No validation errors found in response")
          response)
        (do
          (log/error errors)
          (->500 (Exception. (str "Invalid response payload " errors))))))
    response))

(defn validate-request [{:keys [body] :as request} validations-map mode method model continuation]
  (let [body (request->jsonld request)]
    (if (and @*validate-requests* (some? body))
      (let [_ (log/debug "Validating request for method " method)
            predicate (find-expects-validation validations-map method model)
            _ (log/debug "Found validation predicates " (some? predicate))
            errors (if (some? predicate) (predicate mode validations-map body) [])]
        (log/debug "Request valid? " (empty? errors))
        (if (empty? errors)
          (continuation (assoc request :body body))
          (do
            (log/error errors)
            (->500 (Exception. (str "Invalid response payload " errors))))))
      (continuation (assoc request :body body)))))

(defn process-response [response mode {:keys [validations-map method model] :as context}]
  (let [response-map (if (or (some? (:body response))
                             (some? (:status response)))
                       response
                       {:body response})
        status (:status response-map 200)]
    (if (= status 200)
      (-> response-map
          (assoc :headers (:headers response-map {}))
          (validate-response validations-map mode method model)
          (response->jsonld context)
          (assoc :status status))
      response-map)))

(defn get-handler [request route-params handler context]
  (try
    (log/debug "Executing GET handler")
    (let [response (handler route-params
                            nil
                            request)]
      (log/debug "Got a response")
      (process-response response :read context))
    (catch Exception ex
      (log/error ex)
      (->500 ex))))

(defn post-handler [request route-params handler {:keys [validations-map model] :as context}]
  (try
    (validate-request request validations-map :write :post model
                      (fn [{:keys [body] :as request}]
                        (let [response (handler route-params
                                                body
                                                request)
                              response (process-response response :read context)
                              response (if (= 200 (:status response))
                                         (assoc response :status 201)
                                         response)]
                          response)))
    (catch Exception ex
      (->500 ex))))

(defn put-handler [request route-params handler {:keys [api validations-map model] :as context}]
  (try
    (validate-request request validations-map :update :put model
                      (fn [{:keys [body] :as request}]
                        (let [response (handler route-params
                                                body
                                                request)]
                          (process-response response :read context))))
    (catch Exception ex
      (->500 ex))))

(defn patch-handler [request route-params handler {:keys [api validations-map model] :as context}]
  (try
    (validate-request request validations-map :update :patch model
                      (fn [{:keys [body] :as request}]
                        (let [response (handler route-params
                                                body
                                                request)]
                          (process-response response :read context))))
    (catch Exception ex
      (->500 ex))))

(defn delete-handler [request route-params handler context]
  (try
    (let [response (handler route-params
                            nil
                            request)]
      (process-response response nil context))
    (catch Exception ex
      (->500 ex))))

(defn head-handler [request route-params handler context]
  (-> (get-handler request route-params handler context)
      (assoc :body nil)))

(defn documentation-handler [api]
  (log/debug "API Documentation request")
  (response->jsonld
   {:status 200
    :headers {"Content-Type" "application/ld+json"}
    :body (assoc (hydra/->jsonld api) "@context" (payload/context))}
   api))

(defn fragments-handler [request {:keys [subject predicate object] :as params-map} index]
  (log/debug "API Fragments request")
  (log/debug params-map)
  (let [pattern {:s (when (some? subject) {"@id" subject})
                 :p (when (some? predicate) {"@id" predicate})
                 :o (when (some? object) (payload/parse-triple-component object))}]
    (if (or (some? (:s pattern)) (some? (:p pattern)) (some? (:o pattern))) ;; are they requesting the base uri?
      (let [page (:page params-map "1")
            group (:group params-map "0")
            per-page (:per-page params-map "50")
            page (Integer/parseInt page)
            group(Integer/parseInt group)
            per-page (Integer/parseInt per-page)
            pagination {:page (or page (inc page)) :per-page per-page :group group}
            results-map (index pattern
                               pagination
                               request)]
        ;; generating the actual RDF response in Trix format
        (indexing/index-response params-map results-map request))
      ;; the client is retrieving the base case for the fragments, we just return meta-data
      (indexing/index-response params-map {} request)
      )))

(defn base-url [{:keys [server-port scheme server-name]}]
  (str (name scheme) "://"
       server-name
       (if (not= 80 server-port) (str ":" server-port) "")))

(defn params-map [query-string]
  (-> (if (some? query-string) (url/query->map query-string) {})
      (clojure.walk/keywordize-keys)))

(defn valid-params? [route-params params]
  (log/debug "Validating params " route-params)
  (->> params
       (map (fn [[var-name {:keys [required] :or {required false}}]]
              (log/debug "   " var-name " is a required param : " required)
              (or (not required) (some? (get route-params var-name)))))
       (reduce (fn [acc next-value] (and acc next-value)) true)))

(defn find-model [model api]
  (let [model-uri (if (string? model)
                    model
                    (-> model :common-props ::hydra/id))]
    (if (some? model-uri)
      (->> api
           :supported-classes
           (map (fn [{:keys [common-props supported-properties] :as supported-class}]
                  (if (= (::hydra/id common-props) model-uri)
                    supported-class
                    (let [found-property (->> supported-properties
                                              (filter (fn [{:keys [common-props]}]
                                                        (= (::hydra/id common-props) model-uri)))
                                              first)]
                      (if (some? found-property)
                        found-property
                        nil)))))
           (filter some?)
           first)
      nil)))


(defn middleware [{:keys [api index mount-path routes documentation-path fragments-path] :as context}]
  (let [routes (routing/process-routes routes)
        validations-map (schema/build-api-validations api)
        context (merge context {:routes routes :validations-map validations-map})
        index (indexing/make-indexer api index)]

    (fn [{:keys [uri body request-method query-string] :as request}]
      (log/debug (str "Processing request " request-method " :: " uri))
      (log/debug "Query string: " query-string)
      (log/debug "Body: "body)
      (log/debug "Mountpoint " mount-path)
      (let [request-params  (params-map query-string)
            context (assoc context :request-params request-params)]
        (cond
          (= uri documentation-path) (documentation-handler api)
          (= uri fragments-path)     (fragments-handler request request-params index)
          :else (let [route (string/replace-first uri mount-path "")
                      handler-info (routing/match route)]
                  (if (some? handler-info)
                    (let [{:keys [handlers route-params params model]
                           :or {route-params {} params {}}} handler-info
                          context (-> context
                                      (assoc :base-url (base-url request))
                                      (assoc :model (find-model model api))
                                      (assoc :method request-method))
                          route-params (merge route-params request-params)
                          handler (get handlers request-method)]
                      (log/debug (str "Model "(-> model :common-props ::hydra/id) " :: " (get context :model)))
                      (cond
                        (nil? handler)                            (->405 (str "Method " request-method " not supported"))
                        (not (valid-params? route-params params)) (->422 "Invalid request parameters")
                        :else (condp = request-method
                                :get    (get-handler request route-params handler context)
                                :head   (head-handler request route-params handler context)
                                :post   (post-handler request route-params handler context)
                                :put    (put-handler request route-params handler context)
                                :patch  (patch-handler request route-params handler context)
                                :delete (delete-handler request route-params handler context)
                                :else (->405 (str "Method " request-method " not supported")))))
                    (->404 "Cannot find the requested resource"))))))))

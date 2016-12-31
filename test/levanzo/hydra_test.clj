(ns levanzo.hydra-test
  (:require [clojure.spec :as s]
            [clojure.test :refer :all]
            [levanzo.spec.utils :as spec-utils]
            [levanzo.hydra :as hydra]))

(deftest uri-test
  (is (s/valid? ::hydra/uri "http://test.com/something"))
  (is (s/valid? ::hydra/uri "https://test.com/something"))
  (is (s/valid? ::hydra/uri "file://192.168.40.10/other/thing"))
  (is (not (s/valid? ::hydra/uri "this is not a URI"))))

(deftest curie-test
  (is (s/valid? ::hydra/curie "test:com"))
  (is (s/valid? ::hydra/curie ":name"))
  (is (not (s/valid? ::hydra/uri "this is not a CURIE")))
  (is (not (s/valid? ::hydra/uri ":"))))

(deftest handler-test
 (let [handler (fn [args body request] {})]
    (is (s/valid? ::hydra/handler handler))))

(deftest generic->jsonld-test
  (is (= {"@id" "test"} (hydra/generic->jsonld {::hydra/id "test"} {})))
  (is (= {"hydra:title" "test"} (hydra/generic->jsonld {::hydra/title "test"} {})))
  (is (= {"hydra:title" "test"} (hydra/generic->jsonld {::hydra/title "test"} {"hydra:title" "other"})))
  (is (= {"@type" "test"} (hydra/generic->jsonld {::hydra/type "test"} {})))
  (is (= {"@type" ["other" "test"]} (hydra/generic->jsonld {::hydra/type "test"} {"@type" "other"}))))

(defn operation-jsonld
  ([method expects returns]
   (->> {"@type" "hydra:Operation"
         "hydra:method" method
         "hydra:expects" expects
         "hydra:returns" returns}
        (filter (fn [[k v]] (not (nil? v))))
        (into {})))
  ([] (operation-jsonld "GET"))
  ([method] (operation-jsonld method nil nil)))

(deftest operation-test
  (let [op (hydra/->Operation "hydra:Operation"
                              {}
                              {::hydra/method "GET"
                               ::hydra/expects "test:expects"
                               ::hydra/returns "test:returns"}
                              (fn [args body request]
                                "operation handler"))]
    (is (s/valid? ::hydra/Operation op))
    (is (= "GET" (-> op :operation-props ::hydra/method)))
    (is (= "hydra:Operation" (:term op)))
    (is (= "operation handler" ((:handler op) nil nil nil)))
    (is (= "test:expects" (-> op :operation-props ::hydra/expects)))
    (is (= "test:returns" (-> op :operation-props ::hydra/returns)))
    (is (= (operation-jsonld "GET" "test:expects" "test:returns")
           (hydra/->jsonld op)))))

(deftest operation-helper-tests
  (let [null-handler (fn [_ _ _] true)
        expected {"GET" hydra/get-operation
                  "POST" hydra/post-operation
                  "PATCH" hydra/patch-operation
                  "PUT" hydra/put-operation
                  "DELETE" hydra/delete-operation}]
    (doseq [[expected-method operation-fn] expected]
      (let [operation (operation-fn {::hydra/handler null-handler})]
        (is (= "hydra:Operation" (:term operation)))
        (is (= expected-method (-> operation :operation-props ::hydra/method)))
        (is ((:handler operation) nil nil nil))
        (is (= (operation-jsonld expected-method) (hydra/->jsonld operation)))))))

(deftest property-jsonld-tests
  (let [null-operation (hydra/get-operation {::hydra/handler (fn [_ _ _] true)})
        options {::hydra/readonly true
                 ::hydra/writeonly false
                 ::hydra/required true
                 ::hydra/domain "test:Domain"
                 ::hydra/range "test:Range"
                 ::hydra/operations [null-operation]}

        prop (hydra/->jsonld (hydra/property (assoc options ::hydra/property "test:prop")))
        link (hydra/->jsonld (hydra/link (assoc options ::hydra/property "test:link")))
        template (hydra/->jsonld (hydra/template-link (assoc options ::hydra/property "test:template")))]

    (is (= "hydra:Link" (-> link (get "hydra:property") (get "@type"))))
    (is (= "hydra:TemplatedLink" (-> template (get "hydra:property") (get "@type"))))
    (is (= "rdf:Property" (-> prop (get "hydra:property") (get "@type"))))

    (doseq [supported [prop link template]]
      (is (= true (-> supported (get "hydra:required"))))
      (is (= true (-> supported (get "hydra:readonly"))))
      (is (= false (-> supported (get "hydra:writeonly"))))
      (is (= "test:Domain" (-> supported (get "hydra:property") (get "rdfs:domain"))))
      (is (= "test:Range" (-> supported (get "hydra:property") (get "rdfs:range")))))))

(def test-class (hydra/class {::hydra/id ":MyClass"
                              ::hydra/title "MyClass"
                              ::hydra/description "Test class"
                              ::hydra/operations [(hydra/delete-operation {::hydra/title "Destroys a MyClass instance"
                                                                           ::hydra/handler (fn [_ _ _] "Destroyed")})]
                              ::hydra/supported-properties [(hydra/property {::hydra/property "foaf:name"
                                                                             ::hydra/required true
                                                                             ::hydra/range "xsd:string"})
                                                            (hydra/property {::hydra/property "foaf:age"
                                                                             ::hydra/range "xsd:decimal"})]}))
(deftest class-jsonld-tests
  (is (= {"@id" ":MyClass"
          "@type" "hydra:Class"
          "hydra:title" "MyClass"
          "hydra:description" "Test class"
          "hydra:supportedProperty"
          [{"@type" "hydra:SupportedProperty"
            "hydra:property"
            {"@id" "foaf:name"
             "@type" "rdf:Property"
             "rdfs:range" "xsd:string"
             "hydra:supportedOperation" []}
            "hydra:required" true}
           {"@type" "hydra:SupportedProperty"
            "hydra:property"
            {"@id" "foaf:age"
             "@type" "rdf:Property"
             "rdfs:range" "xsd:decimal"
             "hydra:supportedOperation" []}}]
          "hydra:supportedOperation"
          [{"@type" "hydra:Operation"
            "hydra:method" "DELETE"
            "hydra:title" "Destroys a MyClass instance"}]}
         (hydra/->jsonld test-class))))

(deftest api-documentation-jsonld-tests
  (let [jsonld (hydra/->jsonld (hydra/api {::hydra/id ":MyApi"
                                           ::hydra/title "My Api"
                                           ::hydra/description "Test API"
                                           ::hydra/supported-classes [test-class]}))]
    (is (= ":MyApi" (get jsonld "@id")))
    (is (= "My Api" (get jsonld "hydra:title")))
    (is (= "Test API" (get jsonld "hydra:description")))
    (is (= "hydra:ApiDocumentation" (get jsonld "@type")))
    (is (= 1 (count (get jsonld "hydra:supportedClass"))))
    (is (= ":MyClass" (-> jsonld (get "hydra:supportedClass") first (get "@id"))))))
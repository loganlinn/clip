(ns juxt.clip.core-test
  (:require
    [clojure.test :refer [deftest is are]]
    [juxt.clip.core :as clip]
    [juxt.clip.promesa]
    [juxt.clip.manifold]
    [clojure.edn :as edn]))

(deftest start
  (are [expected start-arg]
       (do
         (is (= expected (clip/start start-arg)))
         (is (= expected @(clip/start (assoc start-arg :executor juxt.clip.promesa/exec))))
         (is (= expected @(clip/start (assoc start-arg :executor juxt.clip.manifold/exec)))))
       {:foo 1 :bar 2}
       {:components
        {:foo {:start 1}
         :bar {:start '(inc (clip/ref :foo))}}}

       {:foo 1 :bar 2}
       {:components
        {:foo {:start 1}
         :bar {:start '(inc (juxt.clip.core/ref :foo))}}}

       {nil 1 :bar 2}
       {:components
        {nil {:start 1}
         :bar {:start '(inc (clip/ref nil))}}}
       
       {:foo 1 :bar 3}
       {:components
        {:foo {:start 1
               :resolve inc}
         :bar {:start '(inc (clip/ref :foo))}}}

       {:foo 1 :bar 3}
       {:components
        {:foo {:start 1
               :resolve inc}
         :bar {:start '(inc (juxt.clip.core/ref :foo))}}}

       {:foo 1 :baz 2}
       {:components
        {:foo {:start 1
               :resolve inc}
         :baz {:start (clip/with-deps
                        [{:keys [foo]}]
                        foo)}}}
       
       {:foo 1 :bar 3 :baz 5}
       {:components
        {:foo {:start 1
               :resolve inc}
         :bar {:start '(inc (clip/ref :foo))}
         :baz {:start (clip/with-deps
                        [{:keys [foo bar]}]
                        (+ foo bar))}}}))

#?(:clj
   (do
     (defn make-delay
       [x]
       (future x))

     (deftest async-values
       (is (= {:foo 1 :bar 2}
              @(clip/start
                 {:components {:foo `{:start (make-delay 1)
                                      :resolve inc}
                               :bar '{:start (clip/ref :foo)}}
                  :executor juxt.clip.manifold/exec}))))))

(deftest start-graph-ex
  (is (thrown? Throwable
               (clip/start
                 {:components
                  {:a {:start (clip/ref :b)}
                   :b {:start (clip/ref :a)}}})))
  (is (thrown? Throwable
               (clip/start
                 {:components
                  {:a {:start (clip/ref :b)}
                   :b {:start (clip/ref :c)}
                   :c {:start (clip/ref :a)}}})))
  (is (thrown? Throwable
               (clip/start
                 {:components
                  {:a {:start (clip/ref :b)}
                   :b {:start (clip/ref :fourohfour)}}}))))

(defn- reverse-contains?
  "contains? but with arguments switched for better `is` compatibility."
  [key coll]
  (contains? coll key))

(deftest start-ex
  (let [partial-failure (try
                          (clip/start
                            {:components
                             {:a {:start "2020"}
                              :b {:start `(+ (clip/ref :a) 20)}}})
                          (catch Throwable t t))]
    (is (reverse-contains? ::clip/system (ex-data partial-failure)))
    (is (reverse-contains? :a (::clip/system (ex-data partial-failure))))
    (is (not (reverse-contains? :b (::clip/system (ex-data partial-failure)))))))

(deftest stop
  (let [stopped (atom {})
        running-system {:foo 1 :bar 2}]
    (clip/stop {:components
                {:foo {:stop (list swap! stopped assoc :foo 'this)}
                 :bar {:stop (list swap! stopped assoc :bar 'this)}}}
               running-system)
    (is (= running-system @stopped)))

  (let [closed? (atom false)
        auto-closable (reify
                        java.lang.AutoCloseable
                        (close [this]
                          (reset! closed? true)))]
    (clip/stop {:components
                {:foo {}}}
               {:foo auto-closable})
    (is (= true @closed?)))

  (let [closed? (atom false)
        auto-closable (reify
                        Object
                        (toString [this]
                          (reset! closed? true)
                          ""))]
    (clip/stop '{:components {:foo {:stop (.toString this)}}}
               {:foo auto-closable})
    (is (= true @closed?))))

(deftest evaluation
  (are [x y] (= x (::foo (clip/start {:components {::foo {:start y}}})))
       2 (list inc 1)
       2 (list 'inc 1)
       2 (list `inc 1)
       2 (cons `inc [1])
       [0 1 2 3 4] '(range 5)
       [1 2 3 4 5] '(map inc (range 5))
       [1 2 3 4 5] `(map inc (range 5))
       [inc] [inc]
       [#'inc] ['inc]
       [2] [(list inc 1)]
       {:foo 2} {:foo (list inc 1)}
       '{foo 2} {'(symbol "foo") (list inc 1)}
       '{foo 2} (edn/read-string (pr-str '{(symbol "foo") (inc 1)}))
       'foo '(symbol "foo")
       "foo" '(str (symbol "foo"))
       {:x 2} (list identity {:x (list inc 1)}))
  (are [x y z] (= x (::foo (clip/start {:components {::init {:start z
                                                             :resolve y}
                                                     ::foo {:start (clip/ref ::init)}}})))
       5 :foo {:foo 5}
       6 'inc 5
       6 inc 5

       (list 1 2 3) :foo '{:foo (list 1 2 3)})
  (are [x] (= (meta x)
              (meta (::foo (clip/start {:components {::foo {:start x}}}))))
       ^:foo [:a :b :c :d]
       ^:foo {:a :b :c :d})
  
  (are [expect started stop-with]
       (= expect (::foo (clip/stop {:components {::foo {:stop stop-with}}}
                                   {::foo started})))
       1 0 inc
       1 0 `inc
       1 0 'inc
       1 {:foo 1} :foo
       (list 1 2 3) (list 1 2 3) identity
       (list 1 2 3) (list 1 2 3) '(identity this)
       1 {:foo (fn [] 1)} '((:foo this)))
  (is
    (= (list 1 2 3)
       (::foo (clip/start
                {:components {::foo {:start (clip/ref ::bar)}
                              ::bar {:start '(list 1 2 3)}}})))))

(deftest select
  (are [x] (= x (clip/select x (keys (:components x))))

       {:components {:a {:start 10}}}
       {:components {:a {:start 10}} :executor 'foo}
       {:components {:a {:start 10} :b {:start 10}}
        :executor 'foo
        :other-key true}
       '{:components {:b {:start 10}
                      :c {:start (inc (clip/ref :b))}}})

  (is (= {:components {:a {:start 10}
                       :b {:start (clip/ref :a)}}}
         (clip/select
           {:components {:a {:start 10}
                         :b {:start (clip/ref :a)}}}
           [:b])))

  (is (= {:components {:a {:start 10}
                        :b {:start (clip/ref :a)}}}
         (clip/select
           {:components {:a {:start 10}
                         :b {:start (clip/ref :a)}
                         :c {:start 20}}}
           [:b])))

  (is (= {:components {:a {:start 10}
                       :e {:start (clip/ref :d)}
                       :d {:start 30}}}
         (clip/select
           {:components {:a {:start 10}
                         :b {:start (clip/ref :a)}
                         :c {:start 20}
                         :d {:start 30}
                         :e {:start (clip/ref :d)}}}
           [:a :e]))))

(deftest partial-start
  (is (= {:a 10}
         (clip/start
           {:components
            {:a {:start 10}
             :b {:start 20}}}
           [:a])))

  (is (= {:a 10
          :b 11}
         (clip/start
           '{:components
             {:a {:start 10}
              :b {:start (inc (clip/ref :a))}}}
           [:b])))

  (is (= {:a 10
          :b 11
          :d 30
          :e 29}
         (clip/start
           '{:components
             {:a {:start 10}
              :b {:start (inc (clip/ref :a))}
              :c {:start 20}
              :d {:start 30}
              :e {:start (dec (clip/ref :d))}}}
           [:b :e])))

  (is (= {:a 10
          :b 11
          :c 20}
         (clip/start
           '{:components
             {:a {:start 10}
              :b {:start (inc (clip/ref :a))}
              :c {:start 20}
              :d {:start 30}
              :e {:start (dec (clip/ref :d))}}}
           [:b :c]))))

#?(:clj
   (deftest with-system-test
     (is (= 10 (clip/with-system [x {:components {:foo {:start 10}}}]
                 (get x :foo))))

     (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Exception thrown while starting system"
           (clip/with-system [x {:components {:foo {:start '(+ "foo")}}}]
             (get x :foo))))

     (let [stopped? (atom false)]
       (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"^juxt.clip.core_test Exception$"
             (clip/with-system [_ {:components {:foo {:start '(+ 1)
                                                      :stop (list reset! stopped? true)}}}]
               (throw (ex-info "juxt.clip.core_test Exception" {})))))
       (is @stopped? "System wasn't stopped when exception thrown in body"))))

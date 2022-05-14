;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.test-propagate
  (:require [jibe.halite.halite-envs :as halite-envs]
            [jibe.halite.propagate :as hp]
            [schema.test])
  (:use clojure.test))

(use-fixtures :once schema.test/validate-schemas)

;; TODO: We need to rewrite 'div forms in the case where the quotient is a variable,
;; to ensure that choco doesn't force the variable to be zero even when the div might not
;; be evaluated.

(deftest test-spec-ify-bound-on-simple-spec
  (let [senv (halite-envs/spec-env
              '{:ws/A
                {:spec-vars {:x "Integer", :y "Integer", :b "Boolean"}
                 :constraints [["c1" (let [delta (abs (- x y))]
                                       (and (< 5 delta)
                                            (< delta 10)))]
                               ["c2" (= b (< x y))]]
                 :refines-to {}}})]

    (is (= '{:spec-vars {:x "Integer", :y "Integer", :b "Boolean"}
             :constraints
             [["$all" (let [$42 (abs (- x y))]
                        (and (and (< 5 $42)
                                  (< $42 10))
                             (= b (< x y))))]]
             :refines-to {}}
           (hp/spec-ify-bound senv {:$type :ws/A})))

    (is (= '{:spec-vars {:x "Integer", :y "Integer", :b "Boolean"}
             :constraints
             [["$all" (let [$42 (abs (- x y))]
                        (and (and (< 5 $42)
                                  (< $42 10))
                             (= b (< x y))
                             (= x 12)
                             (= b false)))]]
             :refines-to {}}
           (hp/spec-ify-bound senv {:$type :ws/A :x 12 :b false})))))

(deftest test-spec-ify-bound-on-instance-valued-exprs
  ;; only integer and boolean valued variables, but expressions may be instance valued
  (let [senv (halite-envs/spec-env
              '{:ws/A
                {:spec-vars {:an "Integer"}
                 :constraints [["a1" (< an 10)]
                               ["a2" (< an (get {:$type :ws/B :bn (+ 1 an)} :bn))]]
                 :refines-to {}}
                :ws/B
                {:spec-vars {:bn "Integer"}
                 :constraints [["b1" (< 0 (get {:$type :ws/C :cn bn} :cn))]]
                 :refines-to {}}
                :ws/C
                {:spec-vars {:cn "Integer"}
                 :constraints [["c1" (= 0 (mod cn 2))]]
                 :refines-to {}}})]
    (is (= '{:spec-vars {:an "Integer"}
             :refines-to {}
             :constraints
             [["$all" (let [$92 (+ 1 an)]
                        (and (< an 10)
                             (if (if (= 0 (mod $92 2))
                                   (< 0 $92)
                                   false)
                               (< an $92)
                               false)))]]}
           (hp/spec-ify-bound senv {:$type :ws/A})))))

(deftest test-spec-ify-bound-on-ifs-and-instance-literals
  (let [senv (halite-envs/spec-env
              '{:ws/A
                {:spec-vars {:an "Integer", :ab "Boolean"}
                 :constraints [["a1" (not=
                                      (if ab
                                        {:$type :ws/B :bn an}
                                        {:$type :ws/B :bn 12})
                                      {:$type :ws/B :bn (+ an 1)})]]
                 :refines-to {}}
                :ws/B
                {:spec-vars {:bn "Integer"}
                 :constraints [["b1" (<= (div 10 bn) 10)]]
                 :refines-to {}}})]
    (is (= '{:spec-vars {:an "Integer" :ab "Boolean"}
             :refines-to {}
             :constraints
             [["$all" (let [$89 (+ an 1)]
                        (if (and (if ab
                                   (<= (div 10 an) 10)
                                   (<= (div 10 12) 10))
                                 (<= (div 10 $89) 10))
                          (not= (if ab an 12) $89)
                          false))]]}
           (hp/spec-ify-bound senv {:$type :ws/A})))))

(deftest test-spec-ify-bound-with-composite-specs
  (testing "simple composition"
    (let [senv (halite-envs/spec-env
                '{:ws/A {:spec-vars {:x "Integer", :y "Integer"}
                         :constraints [["pos" (and (< 0 x) (< 0 y))]
                                       ["boundedSum" (< (+ x y) 20)]]
                         :refines-to {}}
                  :ws/B {:spec-vars {:a1 :ws/A, :a2 :ws/A}
                         :constraints [["a1smaller" (and (< (get a1 :x) (get a2 :x))
                                                         (< (get a1 :y) (get a2 :y)))]]
                         :refines-to {}}})]
      (is (= '{:spec-vars {:a1|x "Integer", :a1|y "Integer", :a2|x "Integer", :a2|y "Integer"}
               :refines-to {}
               :constraints
               [["$all" (and
                         (and (< a1|x a2|x) (< a1|y a2|y))
                         (and (< 0 a1|x) (< 0 a1|y))
                         (< (+ a1|x a1|y) 20)
                         (and (< 0 a2|x) (< 0 a2|y))
                         (< (+ a2|x a2|y) 20))]]}
             (hp/spec-ify-bound senv {:$type :ws/B})))))

  (testing "composition and instance literals"
    (let [senv (halite-envs/spec-env
                '{:ws/C
                  {:spec-vars {:m "Integer", :n "Integer"}
                   :constraints [["c1" (>= m n)]]
                   :refines-to {}}

                  :ws/D
                  {:spec-vars {:c :ws/C :m "Integer"}
                   :constraints [["c1" (= c (let [a 2
                                                  c {:$type :ws/C :m (get c :n) :n (* a m)}
                                                  b 3] c))]]
                   :refines-to {}}})]
      (is (= '{:spec-vars {:c|m "Integer", :c|n "Integer", :m "Integer"}
               :refines-to {}
               :constraints
               [["$all" (let [$79 (* 2 m) $81 (<= $79 c|n)]
                          (and (if $81 (and (= c|m c|n) (= c|n $79)) false) (<= c|n c|m)))]]}
             (hp/spec-ify-bound senv {:$type :ws/D})))))

  (testing "composition of recursive specs"
    ;; Note that due to unconditional recursion there are no finite valid instances of A or C!
    ;; That doesn't prevent us from making the idea of a bound on a recursive spec finite and
    ;; well-defined.
    (let [senv (halite-envs/spec-env
                '{:ws/A
                  {:spec-vars {:b :ws/B :c :ws/C}
                   :constraints [["a1" (= (get b :bn) (get c :cn))]
                                 ["a2" (if (> (get b :bn) 0)
                                         (< (get b :bn)
                                            (get (get (get c :a) :b) :bn))
                                         true)]]
                   :refines-to {}}
                  :ws/B
                  {:spec-vars {:bn "Integer" :bp "Boolean"}
                   :constraints [["b1" (if bp (<= bn 10) (>= bn 10))]]
                   :refines-to {}}
                  :ws/C
                  {:spec-vars {:a :ws/A :cn "Integer"}
                   :constraints []
                   :refines-to {}}})]
      (are [bound choco-spec]
           (= choco-spec (hp/spec-ify-bound senv bound))

        {:$type :ws/A}
        '{:spec-vars {:b|bn "Integer", :b|bp "Boolean", :c|cn "Integer"}
          :refines-to {}
          :constraints
          [["$all" (and
                    (= b|bn c|cn)
                    (if b|bp (<= b|bn 10) (<= 10 b|bn)))]]}

        {:$type :ws/A :b {:$type :ws/B :bn {:$in [2 8]}}}
        '{:spec-vars {:b|bn "Integer", :b|bp "Boolean", :c|cn "Integer"}
          :refines-to {}
          :constraints
          [["$all" (and
                    (= b|bn c|cn)
                    (if b|bp (<= b|bn 10) (<= 10 b|bn))
                    (and (<= 2 b|bn) (<= b|bn 8)))]]}

        #_{:$type :ws/A :b {:$type :ws/B :bn {:$in #{3 4 5}}}}
        #_'{:vars {b|bn :Int, b|bp :Bool, c|cn :Int}
            :constraints
            nil}

        {:$type :ws/A
         :c {:$type :ws/C :cn 14}}
        '{:spec-vars {:b|bn "Integer", :b|bp "Boolean", :c|cn "Integer"}
          :refines-to {}
          :constraints
          [["$all" (and
                    (= b|bn c|cn)
                    (if b|bp (<= b|bn 10) (<= 10 b|bn))
                    (= c|cn 14))]]}

        {:$type :ws/A
         :b {:$type :ws/B :bp true}
         :c {:$type :ws/C
             :a {:$type :ws/A}}}
        '{:spec-vars {:b|bn "Integer", :b|bp "Boolean", :c|a|b|bn "Integer", :c|a|b|bp "Boolean", :c|cn "Integer"}
          :refines-to {}
          :constraints
          [["$all" (let [$54 (< 0 b|bn)]
                    (and
                     (= b|bn c|cn)
                     (if (if $54 true true) (if $54 (< b|bn c|a|b|bn) true) false)
                     (if b|bp (<= b|bn 10) (<= 10 b|bn))
                     (= b|bp true)
                     (if c|a|b|bp (<= c|a|b|bn 10) (<= 10 c|a|b|bn))))]]}))))

(deftest test-spec-ify-bound-on-recursive-composition
  (let [senv (halite-envs/spec-env
              '{:ws/A
                {:spec-vars {:b :ws/B :c :ws/C}
                 :constraints [["a1" (= (get b :bn) (get c :cn))]
                               ["a2" (if (> (get b :bn) 0)
                                       (< (get b :bn)
                                          (get (get (get c :a) :b) :bn))
                                       true)]]
                 :refines-to {}}
                :ws/B
                {:spec-vars {:bn "Integer" :bp "Boolean"}
                 :constraints [["b1" (if bp (<= bn 10) (>= bn 10))]]
                 :refines-to {}}
                :ws/C
                {:spec-vars {:a :ws/A :cn "Integer"}
                 :constraints []
                 :refines-to {}}})]

    (are [b-bound constraint]
         (= {:spec-vars {:bn "Integer", :bp "Boolean"}
             :constraints
             [["$all" constraint]]
             :refines-to {}}
            (hp/spec-ify-bound senv b-bound))

      {:$type :ws/B} '(if bp (<= bn 10) (<= 10 bn))
      {:$type :ws/B :bn 12} '(and (if bp (<= bn 10) (<= 10 bn))
                                  (= bn 12)))

    (is (= '{:spec-vars {:b|bn "Integer" :b|bp "Boolean"
                         :c|cn "Integer"}
             :constraints
             [["$all" (and
                       (= b|bn c|cn)
                       (if b|bp
                         (<= b|bn 10)
                         (<= 10 b|bn)))]]
             :refines-to {}}
           (->> {:$type :ws/A}
                (hp/spec-ify-bound senv))))

    (is (= '{:spec-vars {:b|bn "Integer" :b|bp "Boolean"
                         :c|cn "Integer"
                         :c|a|b|bn "Integer" :c|a|b|bp "Boolean"}
             :constraints
             [["$all" (let [$54 (< 0 b|bn)]
                        (and
                         (= b|bn c|cn)
                         (if (if $54 true true) (if $54 (< b|bn c|a|b|bn) true) false)
                         (if b|bp (<= b|bn 10) (<= 10 b|bn))
                         (= b|bp true)
                         (if c|a|b|bp (<= c|a|b|bn 10) (<= 10 c|a|b|bn))))]]
             :refines-to {}}
           (->> '{:$type :ws/A
                  :b {:$type :ws/B :bp true}
                  :c {:$type :ws/C :a {:$type :ws/A}}}
                (hp/spec-ify-bound senv))))))

(deftest test-propagation-of-trivial-spec
  (let [senv (halite-envs/spec-env
              {:ws/A {:spec-vars {:x "Integer", :y "Integer", :oddSum "Boolean"}
                      :constraints '[["pos" (and (< 0 x) (< 0 y))]
                                     ["y is greater" (< x y)]
                                     ["lines" (let [xysum (+ x y)]
                                                (or (= 42 xysum)
                                                    (= 24 (+ 42 (- 0 xysum)))))]
                                     ["oddSum" (= oddSum (= 1 (mod* (+ x y) 2)))]]
                      :refines-to {}}})
        opts {:default-int-bounds [-100 100]}]
    (are [in out]
         (= out (hp/propagate senv opts in))

      {:$type :ws/A} {:$type :ws/A, :x {:$in [1 99]}, :y {:$in [2 100]}, :oddSum {:$in #{true false}}}

      {:$type :ws/A, :x 8} {:$type :ws/A, :x 8, :y {:$in [9 100]}, :oddSum {:$in #{true false}}}

      {:$type :ws/A, :x 8, :oddSum false} {:$type :ws/A, :x 8, :y {:$in [10 100]}, :oddSum false}

      {:$type :ws/A, :x 10} {:$type :ws/A, :x 10, :y 32, :oddSum false})))

(deftest test-one-to-one-composition
  (let [senv (halite-envs/spec-env
              '{:ws/A {:spec-vars {:x "Integer", :y "Integer"}
                       :constraints [["pos" (and (< 0 x) (< 0 y))]
                                     ["boundedSum" (< (+ x y) 20)]]
                       :refines-to {}}
                :ws/B {:spec-vars {:a1 :ws/A, :a2 :ws/A}
                       :constraints [["a1smaller" (and (< (get a1 :x) (get a2 :x))
                                                       (< (get a1 :y) (get a2 :y)))]]
                       :refines-to {}}})
        opts {:default-int-bounds [-100 100]}]
    (is (= '{:spec-vars {:a1|x "Integer", :a1|y "Integer", :a2|x "Integer", :a2|y "Integer"}
             :constraints [["$all"
                            (and
                             (and (< a1|x a2|x) (< a1|y a2|y))
                             (and (< 0 a1|x) (< 0 a1|y))
                             (< (+ a1|x a1|y) 20)
                             (and (< 0 a2|x) (< 0 a2|y))
                             (< (+ a2|x a2|y) 20))]]
             :refines-to {}}
           (hp/spec-ify-bound senv {:$type :ws/B})))

    (are [in out]
         (= out (hp/propagate senv opts in))

      {:$type :ws/B}
      {:$type :ws/B
       :a1 {:$type :ws/A
            :x {:$in [1 16]}
            :y {:$in [1 16]}}
       :a2 {:$type :ws/A
            :x {:$in [2 17]}
            :y {:$in [2 17]}}}

      {:$type :ws/B
       :a1 {:$type :ws/A
            :x 15}}
      {:$type :ws/B
       :a1 {:$type :ws/A
            :x 15
            :y {:$in [1 2]}}
       :a2 {:$type :ws/A
            :x {:$in [16 17]}
            :y {:$in [2 3]}}})))

(deftest test-recursive-composition
  (let [senv (halite-envs/spec-env
              '{:ws/A
                {:spec-vars {:b :ws/B :c :ws/C}
                 :constraints [["a1" (= (get b :bn) (get c :cn))]
                               ["a2" (if (> (get b :bn) 0)
                                       (< (get b :bn)
                                          (get (get (get c :a) :b) :bn))
                                       true)]]
                 :refines-to {}}
                :ws/B
                {:spec-vars {:bn "Integer" :bp "Boolean"}
                 :constraints [["b1" (if bp (<= bn 10) (>= bn 10))]]
                 :refines-to {}}
                :ws/C
                {:spec-vars {:a :ws/A :cn "Integer"}
                 :constraints []
                 :refines-to {}}})
        opts {:default-int-bounds [-100 100]}]
    (are [in out]
         (= out (hp/propagate senv opts in))

      {:$type :ws/A}
      {:$type :ws/A
       :b {:$type :ws/B
           :bn {:$in [-100 100]}
           :bp {:$in #{false true}}}
       :c {:$type :ws/C
           :a {:$type :ws/A}
           :cn {:$in [-100 100]}}}

      {:$type :ws/A :c {:$type :ws/C :cn 12}}
      {:$type :ws/A
       :b {:$type :ws/B, :bn 12, :bp false}
       :c {:$type :ws/C
           :cn 12
           :a {:$type :ws/A}}}

      {:$type :ws/A :c {:$type :ws/C :a {:$type :ws/A}}}
      {:$type :ws/A,
       :b {:$type :ws/B,
           :bn {:$in [-100 100]},
           :bp {:$in #{false true}}},
       :c {:$type :ws/C,
           :a {:$type :ws/A,
               :b {:$type :ws/B,
                   :bn {:$in [-100 100]},
                   :bp {:$in #{false true}}},
               :c {:$type :ws/C}},
           :cn {:$in [-100 100]}}}

      {:$type :ws/A :b {:$type :ws/B :bp true} :c {:$type :ws/C :a {:$type :ws/A}}}
      {:$type :ws/A,
       :b {:$type :ws/B, :bn {:$in [-100 10]}, :bp true},
       :c {:$type :ws/C,
           :a {:$type :ws/A,
               :b {:$type :ws/B, :bn {:$in [-100 100]}, :bp {:$in #{false true}}},
               :c {:$type :ws/C}},
           :cn {:$in [-100 10]}}})))

(deftest test-instance-literals
  (let [senv (halite-envs/spec-env
              '{:ws/A
                {:spec-vars {:ax "Integer"}
                 :constraints [["c1" (and (< 0 ax) (< ax 10))]]
                 :refines-to {}}

                :ws/B
                {:spec-vars {:by "Integer", :bz "Integer"}
                 :constraints [["c2" (let [a {:$type :ws/A :ax (* 2 by)}
                                           x (get {:$type :ws/A :ax (+ bz bz)} :ax)]
                                       (= x (get {:$type :ws/C :cx (get a :ax)} :cx)))]]
                 :refines-to {}}

                :ws/C
                {:spec-vars {:cx "Integer"}
                 :constraints [["c3" (= cx (get {:$type :ws/A :ax cx} :ax))]]
                 :refines-to {}}})
        opts {:default-int-bounds [-100 100]}]
    (are [in out]
         (= out (hp/propagate senv opts in))

      {:$type :ws/C} {:$type :ws/C :cx {:$in [1 9]}}
      {:$type :ws/B} {:$type :ws/B :by {:$in [1 4]} :bz {:$in [1 4]}}
      {:$type :ws/B :by 2} {:$type :ws/B :by 2 :bz 2})))

(deftest test-propagate-for-refinement
  (let [senv (halite-envs/spec-env
              '{:ws/A
                {:spec-vars {:an "Integer"}
                 :constraints [["a1" (< an 10)]]
                 :refines-to {:ws/B {:expr {:$type :ws/B :bn (+ 1 an)}}}}

                :ws/B
                {:spec-vars {:bn "Integer"}
                 :constraints [["b1" (< 0 bn)]]
                 :refines-to {:ws/C {:expr {:$type :ws/C :cn bn}}}}

                :ws/C
                {:spec-vars {:cn "Integer"}
                 :constraints [["c1" (= 0 (mod cn 2))]]
                 :refines-to {}}

                :ws/D
                {:spec-vars {:a :ws/A, :dm "Integer", :dn "Integer"}
                 :constraints [["d1" (= dm (get (refine-to {:$type :ws/A :an dn} :ws/C) :cn))]
                               ["d2" (= (+ 1 dn) (get (refine-to a :ws/B) :bn))]]
                 :refines-to {}}})]

    (are [spec-id spec]
         (= spec (hp/spec-ify-bound senv {:$type spec-id}))

      :ws/C '{:spec-vars {:cn "Integer"}
              :constraints [["$all" (= 0 (mod cn 2))]]
              :refines-to {}}

      :ws/B '{:spec-vars {:bn "Integer"}
              :constraints
              [["$all" (and (< 0 bn)
                            (= 0 (mod bn 2)))]]
              :refines-to {}}

      :ws/A '{:spec-vars {:an "Integer"}
              :constraints
              [["$all" (let [$103 (+ 1 an)]
                         (and (< an 10)
                              (and (< 0 $103)
                                   (= 0 (mod $103 2)))))]]
              :refines-to {}}

      :ws/D '{:spec-vars {:a|an "Integer", :dm "Integer", :dn "Integer"}
              :constraints
              [["$all"
                (let [$267 (+ 1 dn)
                      $276 (= 0 (mod $267 2))
                      $278 (and (< 0 $267) $276)
                      $280 (and (< dn 10) $278)
                      $286 (if $280 $278 false)
                      $300 (+ 1 a|an)
                      $306 (and (< 0 $300) (= 0 (mod $300 2)))]
                  (and
                   (if (and $280 $280 $286 (if $286 $276 false)) (= dm $267) false)
                   (if $306 (= $267 $300) false)
                   (< a|an 10)
                   $306))
                ;; hand simplification of the above, for validation purposes
                #_(and
                   (< dn 10)                ; a1 as instantiated from d1
                   (< 0 (+ 1 dn))           ; b1 as instantiated from d1 thru A->B
                   (= 0 (mod (+ 1 dn) 2))   ; c1 as instantiated from d1 thru A->B->C
                   (= dm (+ 1 dn))          ; d1 itself
                   (< 0 (+ 1 a|an))         ; b1 as instanted on a thru A->B
                   (= 0 (mod (+ 1 a|an) 2)) ; c1 as instantiated on a thru A->B->C
                   (= (+ 1 dn) (+ 1 a|an))  ; d2 itself
                   (< a|an 10))]]           ; a1 as instantiated on a thru A->B->C
              :refines-to {}})))

(deftest test-spec-ify-for-various-instance-literal-cases
  (let [senv '{:ws/Simpler
               {:spec-vars {:x "Integer", :b "Boolean"}
                :constraints [["posX" (< 0 x)]
                              ["bIfOddX" (= b (= (mod* x 2) 1))]]
                :refines-to {}}

               :ws/Simpler2
               {:spec-vars {:y "Integer"}
                :constraints [["likeSimpler" (= y (get {:$type :ws/Simpler :x y :b false} :x))]]
                :refines-to {}}

               :ws/Test
               {:spec-vars {}
                :constraints []
                :refines-to {}}}]
    (are [constraint choco-spec]
         (= choco-spec
            (-> senv
                (update-in [:ws/Test :constraints] conj ["c1" constraint])
                (halite-envs/spec-env)
                (hp/spec-ify-bound {:$type :ws/Test})
                :constraints first second))

      '(get
        (let [x (+ 1 2)
              s {:$type :ws/Simpler :x (+ x 1) :b false}
              s {:$type :ws/Simpler :x (- (get s :x) 2) :b true}]
          {:$type :ws/Simpler :x 12 :b (get s :b)})
        :b)
      '(let [$133 (+ 1 2)
             $134 (+ $133 1)
             $145 (and (< 0 $134) (= false (= (mod $134 2) 1)))
             $159 (if $145 (let [$147 (- $134 2)] (and (< 0 $147) (= true (= (mod $147 2) 1)))) false)]
         (if (and true $145 $159 (if $159 (and (< 0 12) (= true (= (mod 12 2) 1))) false)) true false))

      '(get {:$type :ws/Simpler :x (get {:$type :ws/Simpler :x 14 :b false} :x) :b true} :b)
      '(let [$85 (< 0 14)
             $92 (= (mod 14 2) 1)]
         (if (if (and $85 (= false $92)) (and $85 (= true $92)) false) true false))

      '(not= 10 (get {:$type :ws/Simpler2 :y 12} :y))
      '(if (if (and (< 0 12) (= false (= (mod 12 2) 1)))
             (= 12 12)
             false)
         (not= 10 12)
         false))))
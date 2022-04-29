;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.transpile.test-lowering
  (:require [jibe.halite.envs :as halite-envs]
            [jibe.halite.transpile.lowering :as lowering]
            [jibe.halite.transpile.ssa :as ssa :refer [Derivations]]
            [jibe.halite.transpile.util :refer [mk-junct]]
            [schema.core :as s]
            [schema.test]
            )
  (:use clojure.test))

(use-fixtures :once schema.test/validate-schemas)

;; TODO: We need to rewrite 'div forms in the case where the quotient is a variable,
;; to ensure that choco doesn't force the variable to be zero even when the div might not
;; be evaluated.


(def lower-instance-comparisons #'lowering/lower-instance-comparisons)

(deftest test-lower-instance-comparisons
  (binding [ssa/*next-id* (atom 0)]
    (let [senv (halite-envs/spec-env
                '{:ws/A
                  {:spec-vars {:an :Integer}
                   :constraints [["a1" (let [b {:$type :ws/B :bn 12 :bb true}]
                                         (and
                                          (= b {:$type :ws/B :bn an :bb true})
                                          (not= {:$type :ws/B :bn 4 :bb false} b)
                                          (= an 45)))]]
                   :refines-to {}}
                  :ws/B
                  {:spec-vars {:bn :Integer :bb :Boolean}
                   :constraints []
                   :refines-to {}}})
          sctx (ssa/build-spec-ctx senv :ws/A)]
      (is (= '[["$all" (let [$4 {:$type :ws/B :bn 12 :bb true}
                             $24 (get* $4 :bn)
                             $6 {:$type :ws/B :bn an :bb true}
                             $18 (get* $4 :bb)
                             $10 {:$type :ws/B :bn 4 :bb false}]
                         (and
                          (and (= $18 (get* $6 :bb))
                               (= $24 (get* $6 :bn)))
                          (or (not= (get* $10 :bb) $18)
                              (not= (get* $10 :bn) $24))
                          (= an 45)))]]
             (-> sctx lower-instance-comparisons :ws/A ssa/spec-from-ssa :constraints)))
      )))

(def fixpoint #'lowering/fixpoint)

(deftest test-lower-instance-comparisons-for-composition
  (binding [ssa/*next-id* (atom 0)]
    (let [senv (halite-envs/spec-env
                '{:ws/A
                  {:spec-vars {:b1 :ws/B, :b2 :ws/B}
                   :constraints [["a1" (not= b1 b2)]]
                   :refines-to {}}
                  :ws/B
                  {:spec-vars {:c1 :ws/C, :c2 :ws/C}
                   :constraints []
                   :refines-to {}}
                  :ws/C
                  {:spec-vars {:x :Integer :y :Integer}
                   :constraints []
                   :refines-to {}}})
          sctx (ssa/build-spec-ctx senv :ws/A)]
      (is (= '[["$all" (let [$6 (get* b2 :c1)
                             $5 (get* b1 :c1)
                             $9 (get* b1 :c2)
                             $10 (get* b2 :c2)]
                         (or
                          (or
                           (not= (get* $5 :x) (get* $6 :x))
                           (not= (get* $5 :y) (get* $6 :y)))
                          (or
                           (not= (get* $9 :x) (get* $10 :x))
                           (not= (get* $9 :y) (get* $10 :y)))))]]
             (->> sctx
                  (fixpoint lower-instance-comparisons)
                  :ws/A ssa/spec-from-ssa :constraints))))))

(def compute-guards #'lowering/compute-guards)
(def form-from-ssa* #'ssa/form-from-ssa*)

(s/defn ^:private guards-from-ssa :- {s/Any s/Any}
  "To facilitate testing"
  [dgraph :- ssa/Derivations, bound :- #{s/Symbol}, guards]
  (-> guards
      (update-keys (partial form-from-ssa* dgraph bound))
      (update-vals
       (fn [guards]
         (if (seq guards)
           (->> guards
                (map #(->> % (map (partial form-from-ssa* dgraph bound)) (mk-junct 'and)))
                (mk-junct 'or))
           true)))))

(deftest test-compute-guards
  (let [senv '{:ws/A
               {:spec-vars {:x :Integer, :y :Integer, :b1 :Boolean, :b2 :Boolean}
                :constraints []
                :refines-to {}}
               :ws/Foo
               {:spec-vars {:x :Integer, :y :Integer}
                :constraints []
                :refines-to {}}}]
    (are [constraints guards]
      (= guards
         (binding [ssa/*next-id* (atom 0)]
           (let [spec-info (-> senv
                               (update-in [:ws/A :constraints] into
                                          (map-indexed #(vector (str "c" %1) %2) constraints))
                               (halite-envs/spec-env)
                               (ssa/build-spec-ctx :ws/A)
                               :ws/A)]
             (-> spec-info
                 (compute-guards)
                 (->> (guards-from-ssa
                       (:derivations spec-info)
                       (->> spec-info :spec-vars keys (map symbol) set))
                      (remove (comp true? val))
                      (into {}))))))

      []
      {}

      '[b1 true]
      {}

      '[(< x y)]
      {}

      '[(if (< x y)
          (< x 10)
          (> x 10))]
      '{(< x 10) (< x y)
        (< 10 x) (<= y x)}

      '[(if (< x y) (<= (div 10 x) 10) true)
        (if (>= x y) (<= (div 10 x) 10) true)]
      '{}

      '[(not= 1 (if b1 (if b2 1 2) 3))]
      '{b2 b1
        2 (and b1 (not b2))
        3 (not b1)
        (if b2 1 2) b1}

      '[(not= 1 (if b1 (if b2 1 2) 3))
        (not= 3 (if (not b2) 2 4))]
      '{4 b2
        (if b2 1 2) b1
        2 (not b2)}

      '[(if b1 {:$type :ws/Foo :x 1 :y 2} {:$type :ws/Foo :x 2 :y 3})]
      '{1 b1
        3 (not b1)
        {:$type :ws/Foo :x 1 :y 2} b1
        {:$type :ws/Foo :x 2 :y 3} (not b1)}

      '[(= 1 (if b1 (get* {:$type :ws/Foo :x 1 :y 2} :x) 3))]
      '{2 b1
        3 (not b1)
        {:$type :ws/Foo :x 1 :y 2} b1
        (get* {:$type :ws/Foo :x 1 :y 2} :x) b1}
      )))

(def lower-implicit-constraints #'lowering/lower-implicit-constraints)

(deftest test-lower-implicit-constraints
  (binding [ssa/*next-id* (atom 0)]
    (let [senv (halite-envs/spec-env
                '{:ws/A
                  {:spec-vars {:an :Integer}
                   :constraints [["a1" (< an 10)]
                                 ["a2" (= an (get* {:$type :ws/B :bn (+ 1 an)} :bn))]]
                   :refines-to {}}
                  :ws/B
                  {:spec-vars {:bn :Integer}
                   :constraints [["b1" (> 0 (if (<= bn 5) (get* {:$type :ws/C :cn bn} :cn) 6))]]
                   :refines-to {}}
                  :ws/C
                  {:spec-vars {:cn :Integer}
                   :constraints [["c1" (= 0 (mod cn 2))]]
                   :refines-to {}}})
          sctx (ssa/build-spec-ctx senv :ws/A)]
      (is (= '[["$all" (let [$6 (+ 1 an)
                             $38 (<= $6 5)]
                         (and
                          (< an 10)
                          (= an (get* {:$type :ws/B, :bn $6} :bn))
                          (and (< (if $38 (get* {:$type :ws/C, :cn $6} :cn) 6) 0)
                               (if $38 (= 0 (mod $6 2)) true))))]]
             (-> sctx lower-implicit-constraints :ws/A ssa/spec-from-ssa :constraints))))))

(def push-gets-into-ifs #'lowering/push-gets-into-ifs)

(deftest test-push-gets-into-ifs
  (binding [ssa/*next-id* (atom 0)]
    (let [senv (halite-envs/spec-env
                '{:ws/A
                  {:spec-vars {:ab :Boolean}
                   :constraints [["a1" (not= 1 (get* (if ab {:$type :ws/B :bn 2} {:$type :ws/B :bn 1}) :bn))]]
                   :refines-to {}}
                  :ws/B
                  {:spec-vars {:bn :Integer}
                   :constraints []
                   :refines-to {}}})
          sctx (ssa/build-spec-ctx senv :ws/A)]
      (is (= '[["$all" (not= 1 (if ab
                                 (get* {:$type :ws/B :bn 2} :bn)
                                 (get* {:$type :ws/B :bn 1} :bn)))]]
             (-> sctx push-gets-into-ifs :ws/A ssa/spec-from-ssa :constraints))))))

(deftest test-push-gets-into-nested-ifs
  (binding [ssa/*next-id* (atom 0)]
    (let [senv (halite-envs/spec-env
                '{:ws/A
                  {:spec-vars {:b1 :ws/B, :b2 :ws/B, :b3 :ws/B, :b4 :ws/B, :a :Boolean, :b :Boolean}
                   :constraints [["a1" (= 12 (get* (if a (if b b1 b2) (if b b3 b4)) :n))]]
                   :refines-to {}}
                  :ws/B
                  {:spec-vars {:n :Integer}
                   :constraints []
                   :refines-to {}}})
          sctx (ssa/build-spec-ctx senv :ws/A)]
      (is (= '[["$all" (= 12 (if a (if b (get* b1 :n) (get* b2 :n)) (if b (get* b3 :n) (get* b4 :n))))]]
             (->> sctx (fixpoint push-gets-into-ifs)
                 :ws/A ssa/spec-from-ssa :constraints))))))

(def cancel-get-of-instance-literal #'lowering/cancel-get-of-instance-literal)

(deftest test-cancel-get-of-instance-literal
  (binding [ssa/*next-id* (atom 0)]
    (let [senv (halite-envs/spec-env
                '{:ws/A
                  {:spec-vars {:an :Integer :b :ws/B}
                   :constraints [["a1" (< an (get* (get* {:$type :ws/B :c {:$type :ws/C :cn (get* {:$type :ws/C :cn (+ 1 an)} :cn)}} :c) :cn))]
                                 ["a2" (= (get* (get* b :c) :cn)
                                          (get* {:$type :ws/C :cn 12} :cn))]]
                   :refines-to {}}
                  :ws/B
                  {:spec-vars {:c :ws/C}
                   :constraints [] :refines-to {}}
                  :ws/C
                  {:spec-vars {:cn :Integer}
                   :constraints [] :refines-to {}}})
          sctx (ssa/build-spec-ctx senv :ws/A)]
      (is (= '[["$all" (and
                        (< an (+ 1 an))
                        (= (get* (get* b :c) :cn) 12))]]
             (->> sctx (fixpoint cancel-get-of-instance-literal) :ws/A ssa/spec-from-ssa :constraints))))))
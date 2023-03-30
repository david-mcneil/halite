;; Copyright (c) 2022,2023 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite.op-canon-refinements
  (:require [com.viasat.halite.bom :as bom]
            [com.viasat.halite.bom-op :as bom-op]
            [com.viasat.halite.spec :as spec]
            [schema.core :as s])
  (:import [com.viasat.halite.lib.fixed_decimal FixedDecimal]))

(set! *warn-on-reflection* true)

(bom-op/def-bom-multimethod canon-refinements-op
  [spec-env bom]

  #{Integer
    FixedDecimal
    String
    Boolean
    bom/NoValueBom
    #{}
    []
    bom/InstanceValue
    bom/PrimitiveBom}
  bom

  bom/ConcreteInstanceBom
  (let [bom (let [{refinements :$refinements} bom]
              (if (zero? (count refinements))
                (dissoc bom :$refinements)
                (let [spec-id (bom/get-spec-id bom)]
                  (reduce (fn [bom' [refinement-spec-id refinement-bom]]
                            (let [refinement-path (spec/find-refinement-path spec-env spec-id refinement-spec-id)]
                              (when (nil? refinement-path)
                                (throw (ex-info "no refinement path" {:bom bom})))
                              ;; need to fill all the map entries on the path
                              (let [bom''' (loop [remaining-path refinement-path
                                                  bom'' bom']
                                             (if (seq remaining-path)
                                               (recur (butlast remaining-path)
                                                      (update-in bom''
                                                                 (interleave (repeat :$refinements)
                                                                             (map :to-spec-id remaining-path))
                                                                 merge
                                                                 (let [to-add {:$instance-of (:to-spec-id (last remaining-path))}]
                                                                   (if (and (not (bom/is-a-no-value-bom? refinement-bom))
                                                                            (:extrinsic? (last remaining-path)))
                                                                     ;; what if accessed field has already been set to false?
                                                                     (assoc to-add :$accessed? true)
                                                                     to-add))))
                                               bom''))]
                                ;; now at the end of the refinement path, put the refinement-bom
                                (update-in bom'''
                                           (interleave (repeat :$refinements)
                                                       (->> refinement-path (map :to-spec-id)))
                                           merge
                                           (canon-refinements-op spec-env refinement-bom)))))
                          (assoc bom :$refinements {})
                          refinements))))]

    ;; process child boms
    (if (bom/is-no-value-bom? bom)
      bom
      (merge bom (-> bom
                     bom/to-bare-instance
                     (update-vals (partial canon-refinements-op spec-env))))))

  bom/AbstractInstanceBom
  bom)

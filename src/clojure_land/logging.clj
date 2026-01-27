(ns clojure-land.logging
  "Logging initialization and convenience wrappers.

   Requiring this namespace initializes Telemere logging.
   Re-exports clojure.tools.logging macros for convenience."
  (:require [clojure.tools.logging :as ctl]
            [taoensso.telemere]
            [taoensso.telemere.tools-logging :refer [tools-logging->telemere!]]))

;; Initialize logging at namespace load time
(tools-logging->telemere!)
(taoensso.telemere/set-min-level!
 (keyword (or (System/getenv "LOG_LEVEL") "info")))

;; Re-export clojure.tools.logging macros
;; These need to be macros to preserve the caller's namespace in log output
(defmacro trace [& args] `(ctl/trace ~@args))
(defmacro debug [& args] `(ctl/debug ~@args))
(defmacro info [& args] `(ctl/info ~@args))
(defmacro warn [& args] `(ctl/warn ~@args))
(defmacro error [& args] `(ctl/error ~@args))
(defmacro fatal [& args] `(ctl/fatal ~@args))

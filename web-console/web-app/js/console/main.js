/* Common parts of the web console. Should be loaded first.
 * 
 * Components can be put in their own files.
 */

/* Everything should be kept in here.
   e.g. Brooklyn.tabs contains the tabs module.
 */
var Brooklyn = {};

/* Used to bind jQuery custom events for the application. */
Brooklyn.eventBus = {};

/* A timer for periodically refreshing data we display. */
var updateInterval = 5000;

function triggerUpdateEvent () {
    $(Brooklyn.eventBus).trigger('update');
}

setInterval(triggerUpdateEvent, updateInterval);
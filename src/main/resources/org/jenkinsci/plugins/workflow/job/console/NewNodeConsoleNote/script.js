var isParallel = false
var nodes = $$('.pipeline-new-node')
var pipelineElements = document.getElementsByClassName("pipeline-new-node")

/**
 * Generate links to show/hide steps from the pipeline,
 * visualize branch names when using parallel builds
 */
function generatePipelineLinks (elements) {
    for (var i = 0; i < elements.length; i++) {
        pipelineElement = elements[i]

        fixLabels(pipelineElement)
        addLinks(pipelineElement)
    }
    generateEnclosings(elements);
}

/**
 * Generate enclosings and labels for the pipeline elements
 *
 * @param nodes
 */
function generateEnclosings (nodes) {
    var enclosings = new Map() // id → enclosingId
    var labels = new Map() // id → label

    for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i]
        var oid = node.getAttribute('nodeId')

        enclosings.set(oid, node.getAttribute('enclosingId'))
        labels.set(oid, node.getAttribute('label'))

        // only if the build has parallel steps
        if (isParallel) {
            appendBranchNames(oid, enclosings, labels)
        }
    }
}

/**
 * If the pipeline has a parallel step, inject the branch name
 *
 * @param nodeId
 * @param enclosings
 * @param labels
 */
function appendBranchNames(nodeId, enclosings, labels) {
    var id = nodeId
    while (true) {
        id = enclosings.get(id)
        if (id == null) {
            break
        }
        var label = labels.get(id)
        if (label != null && label.indexOf('Branch: ') == 0) {
            var branch = label.substring(8)
            var ss = document.styleSheets[0]
            // TODO https://stackoverflow.com/a/18990994/12916 does not scale well to add width: 25em; text-align: right
            ss.insertRule('.pipeline-node-' + nodeId + '::before {content: "[' + branch.escapeHTML() + '] "; color: #9A9999; position: absolute; transform: translateX(-100%)}', ss.cssRules == null ? 0 : ss.cssRules.length)
            break
        }
    }
}

/**
 * Generate hide/show links for all of the pipeline elements
 *
 * @param pipelineElement
 */
function addLinks (pipelineElement) {
       var nodeId = pipelineElement.getAttribute('nodeId')
       var startId = pipelineElement.getAttribute('startId')
       if (startId == null || startId == nodeId) {
            pipelineElement.innerHTML = pipelineElement.innerHTML.replace(/.+/, '$&<span class="pipeline-show-hide"> (<a href="#" onclick="showHidePipelineSection(this); return false">hide</a>)</span>')
       }
}

/**
 * Fix label attributes
 *
 * @param element
 */
function fixLabels(pipelineElement) {
    var label = pipelineElement.getAttribute('label')
    if (label != null) {
        var html = pipelineElement.innerHTML
        var suffix = ' (' + label.escapeHTML() + ')';
        if (html.indexOf(suffix)<0) {
            pipelineElement.innerHTML = pipelineElement.innerHTML.replace(/.+/, '$&' + suffix) // insert before EOL
        }
     }
}

/**
 * Determine if the pipeline contains parallel steps
 */
function isParallelBuild(nodes) {
    for (var i = 0; i < nodes.length; i++) {
        if (nodes[i].innerHTML.includes('parallel')) {
            isParallel = true;
            break;
        }
    }
}

/**
 * Functionality to show/hide a pipeline step (label)
 *
 * @param link - hyperlink element
 **/
function showHidePipelineSection(link) {
    var span = link.parentNode.parentNode
    var id = span.getAttribute('nodeId')
    var display

    if (link.textContent === 'hide') {
        display = 'none'
        link.textContent = 'show'
        link.parentNode.className = ''
    } else {
        display = 'inline'
        link.textContent = 'hide'
        link.parentNode.className = 'pipeline-show-hide'
    }

    var showHide = function (id, display) {
        var sect = '.pipeline-node-' + id
        var ss = document.styleSheets[0]
        for (var i = 0; i < ss.cssRules.length; i++) {
            if (ss.cssRules[i].selectorText === sect) {
                ss.cssRules[i].style.display = display
                return
            }
        }
        ss.insertRule(sect + ' {display: ' + display + '}', ss.cssRules.length)
    }
    showHide(id, display)
    if (span.getAttribute('startId') != null) {
        // For a block node, look up other pipeline-new-node elements parented to this (transitively) and mask them and their text too.
        var nodes = $$('.pipeline-new-node')
        var ids = []
        var starts = new Map()
        var enclosings = new Map() // id → enclosingId

        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i]
            var oid = node.getAttribute('nodeId')
            ids.push(oid)
            starts.set(oid, node.getAttribute('startId'))
            enclosings.set(oid, node.getAttribute('enclosingId'))
        }

        var encloses = function (enclosing, enclosed, starts, enclosings) {
            var id = enclosed
            var start = starts.get(id)
            if (start != null && start != id) {
                id = start // block end node
            }
            while (true) {
                if (id == enclosing) {
                    return true // found it
                }
                id = enclosings.get(id)
                if (id == null) {
                    return false // hit flow start node
                }
            }
        }

        for (var i = 0; i < ids.length; i++) {
            var oid = ids[i]
            if (oid != id && encloses(id, oid, starts, enclosings)) {
                showHide(oid, display)
                var headers = $$('.pipeline-new-node[nodeId=' + oid + ']');
                for (var j = 0; j < headers.length; j++) {
                    headers[j].style.display = display;
                }
                if (display === 'inline') {
                    // Mark all children as shown. TODO would be nicer to leave them collapsed if they were before, but this gets complicated.
                    var links = $$('.pipeline-new-node[nodeId=' + oid + '] span a');
                    for (var j = 0; j < links.length; j++) {
                        links[j].textContent = 'hide';
                        links[j].parentNode.className = 'pipeline-show-hide';
                    }
                }
            }
        }
    }
}

window.addEventListener('load', function() {
    isParallelBuild($$('.pipeline-new-node'));
    generatePipelineLinks($$('.pipeline-new-node'));
});

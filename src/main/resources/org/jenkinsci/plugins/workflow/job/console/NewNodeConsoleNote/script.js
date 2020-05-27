Behaviour.specify("span.pipeline-new-node", 'NewNodeConsoleNote', 0, function (e) {
    /**
     * The setTimeout() method calls a function or evaluates an expression after a specified number of milliseconds.
     * It is used here to prevent a browser freeze that might occur if a very big log is being evaluated.
     */
    setTimeout(() => {
        var nodeId = e.getAttribute('nodeId')
        var oid = e.getAttribute('nodeId')
        var startId = e.getAttribute('startId')
        var label = e.getAttribute('label')
        var nodes = $$('.pipeline-new-node')
        var enclosings = new Map() // id → enclosingId
        var labels = new Map() // id → label

        if (e.processedNewNodeConsoleNote) {
            return
        }
        e.processedNewNodeConsoleNote = true

        if (label != null) {
            var html = e.innerHTML
            var suffix = ' (' + label.escapeHTML() + ')';
            if (html.indexOf(suffix) < 0) {
                e.innerHTML = e.innerHTML.replace(/.+/, '$&' + suffix) // insert before EOL
            }
        }

        // generate hide/show hyperlinks
        if (startId == null || startId == nodeId) {
            e.innerHTML = e.innerHTML.replace(/.+/, '$&<span class="pipeline-show-hide"> (<a href="#" onclick="showHidePipelineSection(this); return false">hide</a>)</span>')
            // TODO automatically hide second and subsequent branches: namely, in case a node has the same parent as an earlier one
        }

        // The CSS rule for branch names only needs to be added once per node, so we
        // check in case we are viewing the truncated log and have already processed
        // a duplicate synthetic span element for this node.
        var maybeDupeNodes = $$('[nodeid=\"' + nodeId + '\"].pipeline-new-node');
        for (var i = 0; i < maybeDupeNodes.length; i++) {
            var node = maybeDupeNodes[i];
            if (node !== e && node.processedNewNodeConsoleNote) {
                return;
            }
        }

        enclosings.set(oid, e.getAttribute('enclosingId'))
        labels.set(oid, e.getAttribute('label'))

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
    }, 5000)
});


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

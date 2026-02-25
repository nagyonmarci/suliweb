app.controller('PdfFormFillerController', ['$scope', '$http', function($scope, $http) {

  $scope.fillPdfForm = function(pdfRequest) {
    $http.post('/pdf/fill', pdfRequest, {
      responseType: 'arraybuffer'
    }).then(function(response) {
      var blob = new Blob([response.data], { type: 'application/pdf' });
      var objectUrl = URL.createObjectURL(blob);
      window.open(objectUrl);
    }, function(error) {
      alert('Hiba történt: ' + error.data);
    });
  };

}]);
